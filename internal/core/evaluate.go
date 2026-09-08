package core

import "time"

func Evaluate(snapshot Snapshot, policy MemberPolicy, localConsent bool, now time.Time, maxSnapshotAge time.Duration) Decision {
	if !localConsent {
		return Decision{Reason: DecisionNoConsent}
	}
	if !policy.Enabled {
		return Decision{Reason: DecisionDisabled}
	}
	if snapshot.MemberID == "" || policy.MemberID != snapshot.MemberID {
		return Decision{Reason: DecisionMemberMismatch}
	}
	if snapshot.CapturedAt.IsZero() || (maxSnapshotAge > 0 && (now.Sub(snapshot.CapturedAt) > maxSnapshotAge || snapshot.CapturedAt.Sub(now) > 2*time.Minute)) {
		return Decision{Reason: DecisionStaleSnapshot}
	}
	if !snapshot.Authenticated {
		return Decision{Reason: DecisionUnauthenticated}
	}

	decision := Decision{Reason: DecisionBelowLimit}
	for i := range policy.Rules {
		rule := policy.Rules[i]
		if rule.Action != ActionLogout || rule.MaxUsedPercent < 0 || rule.MaxUsedPercent > 100 {
			continue
		}
		window := findWindow(snapshot, rule.LimitID, rule.Window)
		if window == nil || !window.ResetsAt.After(now) || window.UsedPercent < rule.MaxUsedPercent {
			continue
		}
		if !decision.ShouldLogout || window.ResetsAt.After(decision.LockedUntil) {
			copyRule := rule
			decision = Decision{
				ShouldLogout: true,
				Reason:       DecisionQuotaExceeded,
				LockedUntil:  window.ResetsAt,
				Rule:         &copyRule,
			}
		}
	}
	return decision
}

func findWindow(snapshot Snapshot, limitID, kind string) *RateLimitWindow {
	for i := range snapshot.Buckets {
		bucket := &snapshot.Buckets[i]
		if bucket.LimitID != limitID {
			continue
		}
		switch kind {
		case WindowPrimary:
			return bucket.Primary
		case WindowSecondary:
			return bucket.Secondary
		default:
			return nil
		}
	}
	return nil
}

