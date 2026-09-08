package service

import (
	"context"
	"crypto/ed25519"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"codex-quota-guard/internal/codex"
	"codex-quota-guard/internal/config"
	"codex-quota-guard/internal/core"
	"codex-quota-guard/internal/store"
)

var ErrLocked = errors.New("managed Codex login is locked until the quota window resets")

type Gateway interface {
	ReadAccount(context.Context) (core.AccountState, error)
	ReadRateLimits(context.Context) (core.RateLimitsResult, error)
	StartDeviceLogin(context.Context) (codex.DeviceLogin, error)
	Logout(context.Context) error
}

type Options struct {
	Now func() time.Time
}

type RuntimeState struct {
	Locked        bool            `json:"locked"`
	LockedUntil   *time.Time      `json:"lockedUntil,omitempty"`
	LockReason    string          `json:"lockReason,omitempty"`
	LockRule      *core.QuotaRule `json:"lockRule,omitempty"`
	LockUpdatedAt *time.Time      `json:"lockUpdatedAt,omitempty"`
	LastRefreshAt *time.Time      `json:"lastRefreshAt,omitempty"`
	LastSuccessAt *time.Time      `json:"lastSuccessAt,omitempty"`
	LastError     string          `json:"lastError,omitempty"`
	LastSnapshot  *core.Snapshot  `json:"lastSnapshot,omitempty"`
	Warnings      []string        `json:"-"`
}

type Guard struct {
	config           config.Config
	store            *store.Store
	gateway          Gateway
	now              func() time.Time
	memberPrivateKey ed25519.PrivateKey
	ownerPublicKey   ed25519.PublicKey
	statePath        string

	operationMu sync.Mutex
	stateMu     sync.RWMutex
	state       RuntimeState
}

func New(cfg config.Config, roomStore *store.Store, gateway Gateway, options Options) (*Guard, error) {
	if err := cfg.Validate(); err != nil {
		return nil, err
	}
	if roomStore == nil || gateway == nil {
		return nil, errors.New("room store and Codex gateway are required")
	}
	memberPrivateKey, err := cfg.MemberPrivateKeyBytes()
	if err != nil {
		return nil, err
	}
	ownerPublicKey, err := cfg.OwnerPublicKeyBytes()
	if err != nil {
		return nil, err
	}
	now := options.Now
	if now == nil {
		now = time.Now
	}
	guard := &Guard{
		config:           cfg,
		store:            roomStore,
		gateway:          gateway,
		now:              now,
		memberPrivateKey: memberPrivateKey,
		ownerPublicKey:   ownerPublicKey,
		statePath:        filepath.Join(filepath.Dir(cfg.ManagedCodexHome), "runtime-state.json"),
	}
	if err := guard.loadState(); err != nil && !errors.Is(err, os.ErrNotExist) {
		return nil, fmt.Errorf("load runtime state: %w", err)
	}
	guard.clearExpiredLock(now().UTC())
	return guard, nil
}

func (g *Guard) Refresh(ctx context.Context) error {
	g.operationMu.Lock()
	defer g.operationMu.Unlock()

	now := g.now().UTC()
	g.clearExpiredLock(now)
	warnings := make([]string, 0)
	memberPolicy, policyAvailable := g.readLocalPolicy(&warnings)

	account, err := g.gateway.ReadAccount(ctx)
	if err != nil {
		g.recordFailure(now, warnings, err)
		return fmt.Errorf("read managed Codex account: %w", err)
	}

	state := g.State()
	if state.Locked && state.LockedUntil != nil && state.LockedUntil.After(now) {
		if account.Authenticated {
			if err := g.gateway.Logout(ctx); err != nil {
				g.recordFailure(now, warnings, err)
				return fmt.Errorf("enforce persisted quota lock: %w", err)
			}
		}
		snapshot := g.lockedSnapshot(now, account)
		if err := g.publish(snapshot); err != nil {
			g.recordFailure(now, warnings, err)
			return err
		}
		g.recordSuccess(now, warnings, snapshot)
		return g.saveState()
	}

	if !account.Authenticated {
		snapshot, err := core.NewSnapshot(g.config.RoomID, g.config.MemberID, g.config.DisplayName, account, core.RateLimitsResult{}, now)
		if err != nil {
			return err
		}
		snapshot.Enforcement = core.EnforcementState{Capable: true, Consent: g.config.EnforcementConsent}
		if err := g.publish(snapshot); err != nil {
			g.recordFailure(now, warnings, err)
			return err
		}
		g.recordSuccess(now, warnings, snapshot)
		return g.saveState()
	}

	limits, err := g.gateway.ReadRateLimits(ctx)
	if err != nil {
		g.recordFailure(now, warnings, err)
		return fmt.Errorf("read managed Codex rate limits: %w", err)
	}
	snapshot, err := core.NewSnapshot(g.config.RoomID, g.config.MemberID, g.config.DisplayName, account, limits, now)
	if err != nil {
		g.recordFailure(now, warnings, err)
		return err
	}
	snapshot.Enforcement = core.EnforcementState{Capable: true, Consent: g.config.EnforcementConsent}

	if policyAvailable {
		decision := core.Evaluate(snapshot, memberPolicy, g.config.EnforcementConsent, now, time.Duration(g.config.StaleAfterSeconds)*time.Second)
		if decision.ShouldLogout {
			if err := g.gateway.Logout(ctx); err != nil {
				g.recordFailure(now, warnings, err)
				return fmt.Errorf("enforce quota policy logout: %w", err)
			}
			snapshot.Authenticated = false
			lockTime := decision.LockedUntil.UTC()
			updatedAt := now
			snapshot.Enforcement = core.EnforcementState{
				Capable: true, Consent: true, Locked: true, LockedUntil: &lockTime,
				Reason: decision.Reason, UpdatedAt: &updatedAt,
			}
			g.stateMu.Lock()
			g.state.Locked = true
			g.state.LockedUntil = &lockTime
			g.state.LockReason = decision.Reason
			g.state.LockRule = decision.Rule
			g.state.LockUpdatedAt = &updatedAt
			g.stateMu.Unlock()
		}
	}

	if err := g.publish(snapshot); err != nil {
		g.recordFailure(now, warnings, err)
		return err
	}
	g.recordSuccess(now, warnings, snapshot)
	return g.saveState()
}

func (g *Guard) StartDeviceLogin(ctx context.Context) (codex.DeviceLogin, error) {
	g.operationMu.Lock()
	defer g.operationMu.Unlock()
	now := g.now().UTC()
	g.clearExpiredLock(now)
	state := g.State()
	if state.Locked && state.LockedUntil != nil && state.LockedUntil.After(now) {
		return codex.DeviceLogin{}, fmt.Errorf("%w: %s", ErrLocked, state.LockedUntil.Format(time.RFC3339))
	}
	return g.gateway.StartDeviceLogin(ctx)
}

func (g *Guard) Logout(ctx context.Context) error {
	g.operationMu.Lock()
	defer g.operationMu.Unlock()
	return g.gateway.Logout(ctx)
}

func (g *Guard) State() RuntimeState {
	g.stateMu.RLock()
	defer g.stateMu.RUnlock()
	state := g.state
	state.Warnings = append([]string(nil), g.state.Warnings...)
	return state
}

func (g *Guard) Run(ctx context.Context) {
	_ = g.Refresh(ctx)
	ticker := time.NewTicker(time.Duration(g.config.RefreshIntervalSeconds) * time.Second)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			refreshContext, cancel := codex.DefaultRequestContext(ctx)
			_ = g.Refresh(refreshContext)
			cancel()
		}
	}
}

func (g *Guard) Config() config.Config {
	return g.config
}

func (g *Guard) readLocalPolicy(warnings *[]string) (core.MemberPolicy, bool) {
	document, err := g.store.ReadPolicy(g.config.RoomID, g.ownerPublicKey)
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			*warnings = append(*warnings, "policy rejected: "+err.Error())
		}
		return core.MemberPolicy{}, false
	}
	policy, found := document.Payload.Members[g.config.MemberID]
	if !found {
		return core.MemberPolicy{}, false
	}
	if policy.MemberID != g.config.MemberID {
		*warnings = append(*warnings, "policy rejected: member identity mismatch")
		return core.MemberPolicy{}, false
	}
	return policy, true
}

func (g *Guard) lockedSnapshot(now time.Time, account core.AccountState) core.Snapshot {
	state := g.State()
	var snapshot core.Snapshot
	if state.LastSnapshot != nil {
		snapshot = *state.LastSnapshot
		snapshot.Buckets = append([]core.RateLimitBucket(nil), state.LastSnapshot.Buckets...)
	} else {
		snapshot, _ = core.NewSnapshot(g.config.RoomID, g.config.MemberID, g.config.DisplayName, account, core.RateLimitsResult{}, now)
	}
	snapshot.PublishedAt = now
	snapshot.Authenticated = false
	snapshot.ManagedLogin = true
	snapshot.Enforcement = core.EnforcementState{
		Capable: true, Consent: g.config.EnforcementConsent, Locked: true,
		LockedUntil: state.LockedUntil, Reason: state.LockReason, UpdatedAt: state.LockUpdatedAt,
	}
	return snapshot
}

func (g *Guard) publish(snapshot core.Snapshot) error {
	document, err := core.Sign(snapshot, g.memberPrivateKey)
	if err != nil {
		return err
	}
	if err := g.store.WriteSnapshot(document); err != nil {
		return fmt.Errorf("publish signed quota snapshot: %w", err)
	}
	return nil
}

func (g *Guard) clearExpiredLock(now time.Time) {
	g.stateMu.Lock()
	defer g.stateMu.Unlock()
	if g.state.Locked && (g.state.LockedUntil == nil || !g.state.LockedUntil.After(now)) {
		g.state.Locked = false
		g.state.LockedUntil = nil
		g.state.LockReason = ""
		g.state.LockRule = nil
		g.state.LockUpdatedAt = nil
	}
}

func (g *Guard) recordFailure(now time.Time, warnings []string, err error) {
	g.stateMu.Lock()
	defer g.stateMu.Unlock()
	g.state.LastRefreshAt = timePointer(now)
	g.state.LastError = sanitizedError(err)
	g.state.Warnings = append([]string(nil), warnings...)
}

func (g *Guard) recordSuccess(now time.Time, warnings []string, snapshot core.Snapshot) {
	g.stateMu.Lock()
	defer g.stateMu.Unlock()
	g.state.LastRefreshAt = timePointer(now)
	g.state.LastSuccessAt = timePointer(now)
	g.state.LastError = ""
	g.state.Warnings = append([]string(nil), warnings...)
	copySnapshot := snapshot
	g.state.LastSnapshot = &copySnapshot
}

func (g *Guard) loadState() error {
	data, err := os.ReadFile(g.statePath)
	if err != nil {
		return err
	}
	if len(data) > 1<<20 {
		return errors.New("runtime state exceeds size limit")
	}
	var state RuntimeState
	if err := json.Unmarshal(data, &state); err != nil {
		return err
	}
	g.state = state
	return nil
}

func (g *Guard) saveState() error {
	g.stateMu.RLock()
	state := g.state
	g.stateMu.RUnlock()
	state.Warnings = nil
	data, err := json.MarshalIndent(state, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	directory := filepath.Dir(g.statePath)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".runtime-state-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, g.statePath); err == nil {
		return nil
	}
	if err := os.Remove(g.statePath); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return os.Rename(temporaryPath, g.statePath)
}

func timePointer(value time.Time) *time.Time {
	copyValue := value
	return &copyValue
}

func sanitizedError(err error) string {
	if err == nil {
		return ""
	}
	message := strings.TrimSpace(err.Error())
	if len(message) > 300 {
		message = message[:300]
	}
	return message
}

