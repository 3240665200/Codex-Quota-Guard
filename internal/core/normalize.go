package core

import (
	"errors"
	"fmt"
	"math"
	"sort"
	"strings"
	"time"
)

func NewSnapshot(roomID, memberID, displayName string, account AccountState, raw RateLimitsResult, capturedAt time.Time) (Snapshot, error) {
	if strings.TrimSpace(roomID) == "" || strings.TrimSpace(memberID) == "" {
		return Snapshot{}, errors.New("room and member identifiers are required")
	}
	if strings.TrimSpace(displayName) == "" {
		return Snapshot{}, errors.New("display name is required")
	}

	inputBuckets := raw.RateLimitsByLimitID
	if len(inputBuckets) == 0 && raw.RateLimits != nil {
		key := raw.RateLimits.LimitID
		if key == "" {
			key = "codex"
		}
		inputBuckets = map[string]RawRateLimitBucket{key: *raw.RateLimits}
	}

	keys := make([]string, 0, len(inputBuckets))
	for key := range inputBuckets {
		keys = append(keys, key)
	}
	sort.Strings(keys)

	buckets := make([]RateLimitBucket, 0, len(keys))
	for _, key := range keys {
		rawBucket := inputBuckets[key]
		if rawBucket.LimitID == "" {
			rawBucket.LimitID = key
		}
		bucket, err := normalizeBucket(rawBucket)
		if err != nil {
			return Snapshot{}, fmt.Errorf("normalize bucket %q: %w", key, err)
		}
		buckets = append(buckets, bucket)
	}

	resetCredits := raw.ResetCreditsAvailable
	if resetCredits == nil && raw.RateLimitResetCredits != nil {
		value := raw.RateLimitResetCredits.AvailableCount
		resetCredits = &value
	}

	return Snapshot{
		SchemaVersion:         SchemaVersion,
		RoomID:                roomID,
		MemberID:              memberID,
		DisplayName:           strings.TrimSpace(displayName),
		CapturedAt:            capturedAt.UTC(),
		PublishedAt:           capturedAt.UTC(),
		Source:                "codex-app-server",
		Authenticated:         account.Authenticated,
		PlanType:              account.PlanType,
		Buckets:               buckets,
		ResetCreditsAvailable: resetCredits,
		ManagedLogin:          true,
	}, nil
}

func normalizeBucket(raw RawRateLimitBucket) (RateLimitBucket, error) {
	if strings.TrimSpace(raw.LimitID) == "" {
		return RateLimitBucket{}, errors.New("limitId is required")
	}
	primary, err := normalizeWindow(raw.Primary)
	if err != nil {
		return RateLimitBucket{}, fmt.Errorf("primary: %w", err)
	}
	secondary, err := normalizeWindow(raw.Secondary)
	if err != nil {
		return RateLimitBucket{}, fmt.Errorf("secondary: %w", err)
	}

	var credits *Credits
	if raw.Credits != nil {
		credits = &Credits{HasCredits: raw.Credits.HasCredits, Unlimited: raw.Credits.Unlimited, Balance: raw.Credits.Balance}
	}

	return RateLimitBucket{
		LimitID:              raw.LimitID,
		LimitName:            raw.LimitName,
		Primary:              primary,
		Secondary:            secondary,
		Credits:              credits,
		PlanType:             raw.PlanType,
		RateLimitReachedType: raw.RateLimitReachedType,
		SpendControlReached:  raw.SpendControlReached,
	}, nil
}

func normalizeWindow(raw *RawRateLimitWindow) (*RateLimitWindow, error) {
	if raw == nil {
		return nil, nil
	}
	if math.IsNaN(raw.UsedPercent) || math.IsInf(raw.UsedPercent, 0) || raw.UsedPercent < 0 || raw.UsedPercent > 100 {
		return nil, fmt.Errorf("usedPercent %.4g is outside 0..100", raw.UsedPercent)
	}
	if raw.WindowDurationMins < 0 {
		return nil, errors.New("windowDurationMins cannot be negative")
	}
	if raw.ResetsAt <= 0 {
		return nil, errors.New("resetsAt must be a Unix timestamp")
	}
	return &RateLimitWindow{
		UsedPercent:        raw.UsedPercent,
		RemainingPercent:   roundPercent(100 - raw.UsedPercent),
		WindowDurationMins: raw.WindowDurationMins,
		ResetsAt:           time.Unix(raw.ResetsAt, 0).UTC(),
	}, nil
}

func roundPercent(value float64) float64 {
	return math.Round(value*1000) / 1000
}

