package codex

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"strings"
	"testing"
	"time"
)

func TestClientInitializesAndReadsAccountAndAllRateLimits(t *testing.T) {
	client, serverDone := newPipeClient(t, func(scanner *bufio.Scanner, writer io.Writer) {
		request := readMessage(t, scanner)
		if request.Method != "initialize" {
			t.Errorf("first method = %q, want initialize", request.Method)
			return
		}
		writeMessage(t, writer, map[string]any{"id": request.ID, "result": map[string]any{"userAgent": "test"}})
		if notification := readMessage(t, scanner); notification.Method != "initialized" || notification.ID != 0 {
			t.Errorf("second message = %#v, want initialized notification", notification)
			return
		}

		request = readMessage(t, scanner)
		if request.Method != "account/read" {
			t.Errorf("method = %q, want account/read", request.Method)
			return
		}
		writeMessage(t, writer, map[string]any{"id": request.ID, "result": map[string]any{
			"account":            map[string]any{"type": "chatgpt", "email": "alice@example.com", "planType": "plus"},
			"requiresOpenaiAuth": true,
		}})

		request = readMessage(t, scanner)
		if request.Method != "account/rateLimits/read" {
			t.Errorf("method = %q, want account/rateLimits/read", request.Method)
			return
		}
		writeMessage(t, writer, map[string]any{"id": request.ID, "result": map[string]any{
			"rateLimitsByLimitId": map[string]any{
				"codex": map[string]any{
					"limitId":   "codex",
					"primary":   map[string]any{"usedPercent": 20.5, "windowDurationMins": 300, "resetsAt": 1788798296},
					"secondary": map[string]any{"usedPercent": 30.25, "windowDurationMins": 10080, "resetsAt": 1789130188},
				},
				"codex_other": map[string]any{
					"limitId": "codex_other",
					"primary": map[string]any{"usedPercent": 4, "windowDurationMins": 60, "resetsAt": 1788798296},
				},
			},
			"rateLimitResetCredits": map[string]any{"availableCount": 2},
		}})
	})
	defer client.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := client.Initialize(ctx, ClientInfo{Name: "test_client", Title: "Test Client", Version: "0.1.0"}); err != nil {
		t.Fatalf("Initialize() error = %v", err)
	}
	account, err := client.ReadAccount(ctx)
	if err != nil {
		t.Fatalf("ReadAccount() error = %v", err)
	}
	if !account.Authenticated || account.PlanType != "plus" || account.Email != "alice@example.com" {
		t.Fatalf("account = %#v", account)
	}
	limits, err := client.ReadRateLimits(ctx)
	if err != nil {
		t.Fatalf("ReadRateLimits() error = %v", err)
	}
	if len(limits.RateLimitsByLimitID) != 2 || limits.RateLimitsByLimitID["codex"].Primary.UsedPercent != 20.5 {
		t.Fatalf("limits = %#v", limits)
	}
	if limits.RateLimitResetCredits == nil || limits.RateLimitResetCredits.AvailableCount != 2 {
		t.Fatalf("reset credits = %#v", limits.RateLimitResetCredits)
	}
	waitServer(t, serverDone)
}

func TestClientOwnsDeviceLoginAndLogoutCeremony(t *testing.T) {
	client, serverDone := newPipeClient(t, func(scanner *bufio.Scanner, writer io.Writer) {
		initialize := readMessage(t, scanner)
		writeMessage(t, writer, map[string]any{"id": initialize.ID, "result": map[string]any{}})
		_ = readMessage(t, scanner)

		login := readMessage(t, scanner)
		if login.Method != "account/login/start" || !strings.Contains(string(login.Params), "chatgptDeviceCode") {
			t.Errorf("login request = %#v", login)
			return
		}
		writeMessage(t, writer, map[string]any{"id": login.ID, "result": map[string]any{
			"type": "chatgptDeviceCode", "loginId": "login_1", "verificationUrl": "https://auth.openai.com/codex/device", "userCode": "ABCD-1234",
		}})

		logout := readMessage(t, scanner)
		if logout.Method != "account/logout" {
			t.Errorf("logout method = %q", logout.Method)
			return
		}
		writeMessage(t, writer, map[string]any{"id": logout.ID, "result": map[string]any{}})
	})
	defer client.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := client.Initialize(ctx, ClientInfo{Name: "test", Title: "Test", Version: "1"}); err != nil {
		t.Fatal(err)
	}
	login, err := client.StartDeviceLogin(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if login.LoginID != "login_1" || login.UserCode != "ABCD-1234" || login.VerificationURL != "https://auth.openai.com/codex/device" {
		t.Fatalf("login = %#v", login)
	}
	if err := client.Logout(ctx); err != nil {
		t.Fatalf("Logout() error = %v", err)
	}
	waitServer(t, serverDone)
}

func TestClientSurfacesJSONRPCErrorWithoutHanging(t *testing.T) {
	client, serverDone := newPipeClient(t, func(scanner *bufio.Scanner, writer io.Writer) {
		initialize := readMessage(t, scanner)
		writeMessage(t, writer, map[string]any{"id": initialize.ID, "result": map[string]any{}})
		_ = readMessage(t, scanner)
		request := readMessage(t, scanner)
		writeMessage(t, writer, map[string]any{"id": request.ID, "error": map[string]any{"code": -32000, "message": "not authenticated"}})
	})
	defer client.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := client.Initialize(ctx, ClientInfo{Name: "test", Title: "Test", Version: "1"}); err != nil {
		t.Fatal(err)
	}
	_, err := client.ReadRateLimits(ctx)
	if err == nil || !strings.Contains(err.Error(), "not authenticated") {
		t.Fatalf("ReadRateLimits() error = %v", err)
	}
	waitServer(t, serverDone)
}

type wireMessage struct {
	Method string          `json:"method"`
	ID     int64           `json:"id"`
	Params json.RawMessage `json:"params"`
}

func newPipeClient(t *testing.T, server func(*bufio.Scanner, io.Writer)) (*Client, <-chan struct{}) {
	t.Helper()
	serverInput, clientOutput := io.Pipe()
	clientInput, serverOutput := io.Pipe()
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer serverInput.Close()
		defer serverOutput.Close()
		server(bufio.NewScanner(serverInput), serverOutput)
	}()
	return NewWithTransport(clientOutput, clientInput, func() error {
		_ = clientOutput.Close()
		return clientInput.Close()
	}), done
}

func readMessage(t *testing.T, scanner *bufio.Scanner) wireMessage {
	t.Helper()
	if !scanner.Scan() {
		t.Fatalf("server failed to read message: %v", scanner.Err())
	}
	var message wireMessage
	if err := json.Unmarshal(scanner.Bytes(), &message); err != nil {
		t.Fatalf("decode client message: %v", err)
	}
	return message
}

func writeMessage(t *testing.T, writer io.Writer, message any) {
	t.Helper()
	data, err := json.Marshal(message)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := writer.Write(append(data, '\n')); err != nil {
		t.Fatalf("write server message: %v", err)
	}
}

func waitServer(t *testing.T, done <-chan struct{}) {
	t.Helper()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("fake app-server did not finish")
	}
}

