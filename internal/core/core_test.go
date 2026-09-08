package core

import (
	"crypto/ed25519"
	"encoding/json"
	"testing"
	"time"
)

func TestNewSnapshotUsesAllServerBucketsWithoutLeakingEmail(t *testing.T) {
	now := time.Date(2026, 9, 7, 14, 0, 0, 0, time.UTC)
	reset5h := now.Add(2 * time.Hour).Unix()
	resetWeek := now.Add(4 * 24 * time.Hour).Unix()
	resetOther := now.Add(30 * time.Minute).Unix()

	snapshot, err := NewSnapshot("room_1", "member_alice", "Alice", AccountState{
		Authenticated: true,
		PlanType:      "plus",
		Email:         "alice@example.com",
	}, RateLimitsResult{
		RateLimitsByLimitID: map[string]RawRateLimitBucket{
			"codex_other": {
				LimitID:   "codex_other",
				LimitName: "Fast model",
				Primary:   &RawRateLimitWindow{UsedPercent: 44.5, WindowDurationMins: 60, ResetsAt: resetOther},
			},
			"codex": {
				LimitID:  "codex",
				PlanType: "plus",
				Primary:  &RawRateLimitWindow{UsedPercent: 23, WindowDurationMins: 300, ResetsAt: reset5h},
				Secondary: &RawRateLimitWindow{
					UsedPercent: 33, WindowDurationMins: 10080, ResetsAt: resetWeek,
				},
			},
		},
		ResetCreditsAvailable: ptrInt(1),
	}, now)
	if err != nil {
		t.Fatalf("NewSnapshot() error = %v", err)
	}

	if len(snapshot.Buckets) != 2 {
		t.Fatalf("bucket count = %d, want 2", len(snapshot.Buckets))
	}
	if snapshot.Buckets[0].LimitID != "codex" || snapshot.Buckets[1].LimitID != "codex_other" {
		t.Fatalf("buckets are not deterministically sorted: %#v", snapshot.Buckets)
	}
	primary := snapshot.Buckets[0].Primary
	if primary == nil || primary.UsedPercent != 23 || primary.RemainingPercent != 77 {
		t.Fatalf("primary window = %#v", primary)
	}
	if !primary.ResetsAt.Equal(time.Unix(reset5h, 0).UTC()) {
		t.Fatalf("resetsAt = %s", primary.ResetsAt)
	}
	if snapshot.ResetCreditsAvailable == nil || *snapshot.ResetCreditsAvailable != 1 {
		t.Fatalf("reset credits = %#v", snapshot.ResetCreditsAvailable)
	}

	encoded, err := json.Marshal(snapshot)
	if err != nil {
		t.Fatal(err)
	}
	if containsString(string(encoded), "alice@example.com") || containsString(string(encoded), "email") {
		t.Fatalf("shared snapshot leaked email: %s", encoded)
	}
}

func TestNewSnapshotFallsBackToLegacySingleBucket(t *testing.T) {
	now := time.Now().UTC().Truncate(time.Second)
	snapshot, err := NewSnapshot("room_1", "member_1", "Member", AccountState{Authenticated: true}, RateLimitsResult{
		RateLimits: &RawRateLimitBucket{
			LimitID: "codex",
			Primary: &RawRateLimitWindow{UsedPercent: 10, WindowDurationMins: 300, ResetsAt: now.Add(time.Hour).Unix()},
		},
	}, now)
	if err != nil {
		t.Fatal(err)
	}
	if len(snapshot.Buckets) != 1 || snapshot.Buckets[0].LimitID != "codex" {
		t.Fatalf("legacy bucket was not preserved: %#v", snapshot.Buckets)
	}
}

func TestEvaluatePolicyTriggersLogoutAndLocksUntilReset(t *testing.T) {
	now := time.Date(2026, 9, 7, 14, 0, 0, 0, time.UTC)
	reset := now.Add(90 * time.Minute)
	snapshot := Snapshot{
		SchemaVersion: SchemaVersion,
		RoomID:        "room_1", MemberID: "member_alice", CapturedAt: now,
		Authenticated: true,
		Buckets: []RateLimitBucket{{
			LimitID: "codex",
			Primary: &RateLimitWindow{UsedPercent: 80, RemainingPercent: 20, WindowDurationMins: 300, ResetsAt: reset},
		}},
	}
	policy := MemberPolicy{
		MemberID: "member_alice", Enabled: true,
		Rules: []QuotaRule{{LimitID: "codex", Window: WindowPrimary, MaxUsedPercent: 80, Action: ActionLogout}},
	}

	decision := Evaluate(snapshot, policy, true, now.Add(5*time.Second), time.Minute)
	if !decision.ShouldLogout {
		t.Fatalf("ShouldLogout = false, reason = %s", decision.Reason)
	}
	if !decision.LockedUntil.Equal(reset) {
		t.Fatalf("LockedUntil = %s, want %s", decision.LockedUntil, reset)
	}
	if decision.Rule == nil || decision.Rule.Window != WindowPrimary {
		t.Fatalf("triggering rule = %#v", decision.Rule)
	}
}

func TestEvaluatePolicyFailsSafeForStaleOrResetWindow(t *testing.T) {
	now := time.Date(2026, 9, 7, 14, 0, 0, 0, time.UTC)
	policy := MemberPolicy{MemberID: "member_1", Enabled: true, Rules: []QuotaRule{{
		LimitID: "codex", Window: WindowPrimary, MaxUsedPercent: 50, Action: ActionLogout,
	}}}

	stale := Snapshot{RoomID: "room_1", MemberID: "member_1", CapturedAt: now.Add(-10 * time.Minute), Authenticated: true,
		Buckets: []RateLimitBucket{{LimitID: "codex", Primary: &RateLimitWindow{UsedPercent: 99, ResetsAt: now.Add(time.Hour)}}}}
	if got := Evaluate(stale, policy, true, now, time.Minute); got.ShouldLogout || got.Reason != DecisionStaleSnapshot {
		t.Fatalf("stale decision = %#v", got)
	}

	reset := stale
	reset.CapturedAt = now
	reset.Buckets[0].Primary.ResetsAt = now.Add(-time.Second)
	if got := Evaluate(reset, policy, true, now, time.Minute); got.ShouldLogout {
		t.Fatalf("expired window triggered logout: %#v", got)
	}
}

func TestEvaluatePolicyRequiresLocalConsent(t *testing.T) {
	now := time.Now().UTC()
	snapshot := Snapshot{RoomID: "room_1", MemberID: "member_1", CapturedAt: now, Authenticated: true,
		Buckets: []RateLimitBucket{{LimitID: "codex", Primary: &RateLimitWindow{UsedPercent: 100, ResetsAt: now.Add(time.Hour)}}}}
	policy := MemberPolicy{MemberID: "member_1", Enabled: true, Rules: []QuotaRule{{
		LimitID: "codex", Window: WindowPrimary, MaxUsedPercent: 1, Action: ActionLogout,
	}}}
	if got := Evaluate(snapshot, policy, false, now, time.Minute); got.ShouldLogout || got.Reason != DecisionNoConsent {
		t.Fatalf("decision without consent = %#v", got)
	}
}

func TestSignedDocumentsRejectTamperingAndCrossRoomReplay(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	payload := Policy{SchemaVersion: SchemaVersion, RoomID: "room_1", Revision: 3, Members: map[string]MemberPolicy{
		"member_1": {MemberID: "member_1", Enabled: true},
	}}
	signed, err := Sign(payload, privateKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := Verify(signed, publicKey); err != nil {
		t.Fatalf("Verify() error = %v", err)
	}

	tampered := signed
	tampered.Payload.Revision = 4
	if err := Verify(tampered, publicKey); err == nil {
		t.Fatal("tampered policy unexpectedly verified")
	}

	replayed := signed
	replayed.Payload.RoomID = "room_2"
	if err := Verify(replayed, publicKey); err == nil {
		t.Fatal("cross-room replay unexpectedly verified")
	}
}

func ptrInt(value int) *int { return &value }

func containsString(value, fragment string) bool {
	for i := 0; i+len(fragment) <= len(value); i++ {
		if value[i:i+len(fragment)] == fragment {
			return true
		}
	}
	return false
}

