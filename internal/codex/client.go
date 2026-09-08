package codex

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"codex-quota-guard/internal/core"
)

const maxRPCMessageBytes = 4 << 20

type ClientInfo struct {
	Name    string `json:"name"`
	Title   string `json:"title"`
	Version string `json:"version"`
}

type DeviceLogin struct {
	Type            string `json:"type"`
	LoginID         string `json:"loginId"`
	VerificationURL string `json:"verificationUrl"`
	UserCode        string `json:"userCode"`
}

type Options struct {
	Executable string
	CodexHome  string
	ClientInfo ClientInfo
}

type rpcResponse struct {
	ID     *int64          `json:"id"`
	Result json.RawMessage `json:"result"`
	Error  *rpcError       `json:"error"`
}

type rpcError struct {
	Code    int    `json:"code"`
	Message string `json:"message"`
}

type Client struct {
	input   io.WriteCloser
	output  io.ReadCloser
	closeFn func() error
	process *exec.Cmd

	encoder *json.Encoder
	writeMu sync.Mutex
	nextID  atomic.Int64

	pendingMu sync.Mutex
	pending   map[int64]chan rpcResponse
	done      chan struct{}
	readErr   error
	closeOnce sync.Once

	initialized atomic.Bool
}

func NewWithTransport(input io.WriteCloser, output io.ReadCloser, closeFn func() error) *Client {
	client := &Client{
		input:   input,
		output:  output,
		closeFn: closeFn,
		encoder: json.NewEncoder(input),
		pending: make(map[int64]chan rpcResponse),
		done:    make(chan struct{}),
	}
	go client.readLoop()
	return client
}

func Start(ctx context.Context, options Options) (*Client, error) {
	executable := strings.TrimSpace(options.Executable)
	if executable == "" {
		executable = "codex"
	}
	if !filepath.IsAbs(options.CodexHome) {
		return nil, errors.New("managed CODEX_HOME must be an absolute path")
	}
	if err := os.MkdirAll(options.CodexHome, 0o700); err != nil {
		return nil, fmt.Errorf("create managed CODEX_HOME: %w", err)
	}

	command := exec.Command(executable, "app-server")
	command.Env = replaceEnvironment(os.Environ(), "CODEX_HOME", options.CodexHome)
	stdin, err := command.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := command.StdoutPipe()
	if err != nil {
		return nil, err
	}
	command.Stderr = io.Discard
	if err := command.Start(); err != nil {
		return nil, fmt.Errorf("start Codex app-server: %w", err)
	}

	client := NewWithTransport(stdin, stdout, func() error {
		_ = stdin.Close()
		_ = stdout.Close()
		if command.Process != nil {
			_ = command.Process.Kill()
		}
		return command.Wait()
	})
	client.process = command
	info := options.ClientInfo
	if info.Name == "" {
		info = ClientInfo{Name: "codex_quota_guard", Title: "Codex Quota Guard", Version: "0.1.0"}
	}
	if err := client.Initialize(ctx, info); err != nil {
		_ = client.Close()
		return nil, err
	}
	return client, nil
}

func (c *Client) Initialize(ctx context.Context, info ClientInfo) error {
	if strings.TrimSpace(info.Name) == "" || strings.TrimSpace(info.Title) == "" || strings.TrimSpace(info.Version) == "" {
		return errors.New("complete client metadata is required")
	}
	var result struct {
		UserAgent string `json:"userAgent"`
	}
	if err := c.call(ctx, "initialize", map[string]any{"clientInfo": info}, &result); err != nil {
		return fmt.Errorf("initialize Codex app-server: %w", err)
	}
	if err := c.notify("initialized", map[string]any{}); err != nil {
		return fmt.Errorf("acknowledge Codex app-server initialization: %w", err)
	}
	c.initialized.Store(true)
	return nil
}

func (c *Client) ReadAccount(ctx context.Context) (core.AccountState, error) {
	if err := c.requireInitialized(); err != nil {
		return core.AccountState{}, err
	}
	var result struct {
		Account *struct {
			Type     string `json:"type"`
			Email    string `json:"email"`
			PlanType string `json:"planType"`
		} `json:"account"`
	}
	if err := c.call(ctx, "account/read", map[string]any{"refreshToken": false}, &result); err != nil {
		return core.AccountState{}, err
	}
	if result.Account == nil {
		return core.AccountState{}, nil
	}
	return core.AccountState{Authenticated: true, PlanType: result.Account.PlanType, Email: result.Account.Email}, nil
}

func (c *Client) ReadRateLimits(ctx context.Context) (core.RateLimitsResult, error) {
	if err := c.requireInitialized(); err != nil {
		return core.RateLimitsResult{}, err
	}
	var result core.RateLimitsResult
	if err := c.call(ctx, "account/rateLimits/read", map[string]any{}, &result); err != nil {
		return result, err
	}
	return result, nil
}

func (c *Client) StartDeviceLogin(ctx context.Context) (DeviceLogin, error) {
	if err := c.requireInitialized(); err != nil {
		return DeviceLogin{}, err
	}
	var result DeviceLogin
	if err := c.call(ctx, "account/login/start", map[string]any{"type": "chatgptDeviceCode"}, &result); err != nil {
		return result, err
	}
	return result, nil
}

func (c *Client) CancelLogin(ctx context.Context, loginID string) error {
	if err := c.requireInitialized(); err != nil {
		return err
	}
	if strings.TrimSpace(loginID) == "" {
		return errors.New("login identifier is required")
	}
	var result struct{}
	return c.call(ctx, "account/login/cancel", map[string]any{"loginId": loginID}, &result)
}

func (c *Client) Logout(ctx context.Context) error {
	if err := c.requireInitialized(); err != nil {
		return err
	}
	var result struct{}
	return c.call(ctx, "account/logout", map[string]any{}, &result)
}

func (c *Client) Close() error {
	var err error
	c.closeOnce.Do(func() {
		if c.closeFn != nil {
			err = c.closeFn()
		} else {
			_ = c.input.Close()
			err = c.output.Close()
		}
	})
	return err
}

func (c *Client) requireInitialized() error {
	if !c.initialized.Load() {
		return errors.New("Codex app-server client is not initialized")
	}
	return nil
}

func (c *Client) call(ctx context.Context, method string, params any, target any) error {
	id := c.nextID.Add(1)
	responseChannel := make(chan rpcResponse, 1)
	c.pendingMu.Lock()
	c.pending[id] = responseChannel
	c.pendingMu.Unlock()
	defer func() {
		c.pendingMu.Lock()
		delete(c.pending, id)
		c.pendingMu.Unlock()
	}()

	if err := c.send(map[string]any{"method": method, "id": id, "params": params}); err != nil {
		return err
	}
	select {
	case response := <-responseChannel:
		if response.Error != nil {
			return fmt.Errorf("Codex app-server error %d: %s", response.Error.Code, response.Error.Message)
		}
		if target == nil || len(response.Result) == 0 || string(response.Result) == "null" {
			return nil
		}
		if err := json.Unmarshal(response.Result, target); err != nil {
			return fmt.Errorf("decode %s result: %w", method, err)
		}
		return nil
	case <-ctx.Done():
		return fmt.Errorf("%s: %w", method, ctx.Err())
	case <-c.done:
		c.pendingMu.Lock()
		readErr := c.readErr
		c.pendingMu.Unlock()
		if readErr == nil {
			readErr = io.EOF
		}
		return fmt.Errorf("Codex app-server stopped: %w", readErr)
	}
}

func (c *Client) notify(method string, params any) error {
	return c.send(map[string]any{"method": method, "params": params})
}

func (c *Client) send(message any) error {
	c.writeMu.Lock()
	defer c.writeMu.Unlock()
	select {
	case <-c.done:
		return errors.New("Codex app-server connection is closed")
	default:
	}
	if err := c.encoder.Encode(message); err != nil {
		return fmt.Errorf("write Codex app-server request: %w", err)
	}
	return nil
}

func (c *Client) readLoop() {
	scanner := bufio.NewScanner(c.output)
	scanner.Buffer(make([]byte, 64*1024), maxRPCMessageBytes)
	for scanner.Scan() {
		var response rpcResponse
		if err := json.Unmarshal(scanner.Bytes(), &response); err != nil || response.ID == nil {
			continue
		}
		c.pendingMu.Lock()
		channel := c.pending[*response.ID]
		c.pendingMu.Unlock()
		if channel != nil {
			select {
			case channel <- response:
			default:
			}
		}
	}
	c.pendingMu.Lock()
	c.readErr = scanner.Err()
	c.pendingMu.Unlock()
	close(c.done)
}

func replaceEnvironment(environment []string, key, value string) []string {
	prefix := key + "="
	result := make([]string, 0, len(environment)+1)
	for _, entry := range environment {
		matches := strings.HasPrefix(entry, prefix)
		if runtime.GOOS == "windows" {
			matches = strings.EqualFold(strings.SplitN(entry, "=", 2)[0], key)
		}
		if !matches {
			result = append(result, entry)
		}
	}
	return append(result, prefix+value)
}

func DefaultRequestContext(parent context.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(parent, 20*time.Second)
}

