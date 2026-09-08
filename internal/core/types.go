package core

import "time"

const SchemaVersion = 1

const (
	WindowPrimary   = "primary"
	WindowSecondary = "secondary"

	ActionLogout = "logout"
)

const (
	DecisionBelowLimit      = "below_limit"
	DecisionDisabled        = "policy_disabled"
	DecisionMemberMismatch  = "member_mismatch"
	DecisionNoConsent       = "local_consent_required"
	DecisionStaleSnapshot   = "stale_snapshot"
	DecisionUnauthenticated = "not_authenticated"
	DecisionQuotaExceeded   = "quota_exceeded"
)

type AccountState struct {
	Authenticated bool
	PlanType      string
	Email         string
}

type RawRateLimitWindow struct {
	UsedPercent        float64 `json:"usedPercent"`
	WindowDurationMins int64   `json:"windowDurationMins"`
	ResetsAt           int64   `json:"resetsAt"`
}

type RawCredits struct {
	HasCredits bool   `json:"hasCredits"`
	Unlimited  bool   `json:"unlimited"`
	Balance    string `json:"balance"`
}

type RawRateLimitBucket struct {
	LimitID              string              `json:"limitId"`
	LimitName            string              `json:"limitName"`
	Primary              *RawRateLimitWindow `json:"primary"`
	Secondary            *RawRateLimitWindow `json:"secondary"`
	Credits              *RawCredits         `json:"credits"`
	PlanType             string              `json:"planType"`
	RateLimitReachedType string              `json:"rateLimitReachedType"`
	SpendControlReached  bool                `json:"spendControlReached"`
}

type RawResetCredits struct {
	AvailableCount int `json:"availableCount"`
}

type RateLimitsResult struct {
	RateLimits            *RawRateLimitBucket           `json:"rateLimits"`
	RateLimitsByLimitID   map[string]RawRateLimitBucket `json:"rateLimitsByLimitId"`
	RateLimitResetCredits *RawResetCredits              `json:"rateLimitResetCredits"`
	ResetCreditsAvailable *int                          `json:"-"`
}

type RateLimitWindow struct {
	UsedPercent        float64   `json:"usedPercent"`
	RemainingPercent   float64   `json:"remainingPercent"`
	WindowDurationMins int64     `json:"windowDurationMins"`
	ResetsAt           time.Time `json:"resetsAt"`
}

type Credits struct {
	HasCredits bool   `json:"hasCredits"`
	Unlimited  bool   `json:"unlimited"`
	Balance    string `json:"balance"`
}

type RateLimitBucket struct {
	LimitID              string           `json:"limitId"`
	LimitName            string           `json:"limitName,omitempty"`
	Primary              *RateLimitWindow `json:"primary,omitempty"`
	Secondary            *RateLimitWindow `json:"secondary,omitempty"`
	Credits              *Credits         `json:"credits,omitempty"`
	PlanType             string           `json:"planType,omitempty"`
	RateLimitReachedType string           `json:"rateLimitReachedType,omitempty"`
	SpendControlReached  bool             `json:"spendControlReached"`
}

type EnforcementState struct {
	Capable     bool       `json:"capable"`
	Consent     bool       `json:"consent"`
	Locked      bool       `json:"locked"`
	LockedUntil *time.Time `json:"lockedUntil,omitempty"`
	Reason      string     `json:"reason,omitempty"`
	UpdatedAt   *time.Time `json:"updatedAt,omitempty"`
}

type Snapshot struct {
	SchemaVersion         int               `json:"schemaVersion"`
	RoomID                string            `json:"roomId"`
	MemberID              string            `json:"memberId"`
	DisplayName           string            `json:"displayName"`
	CapturedAt            time.Time         `json:"capturedAt"`
	PublishedAt           time.Time         `json:"publishedAt"`
	Source                string            `json:"source"`
	Authenticated         bool              `json:"authenticated"`
	PlanType              string            `json:"planType,omitempty"`
	Buckets               []RateLimitBucket `json:"buckets"`
	ResetCreditsAvailable *int              `json:"resetCreditsAvailable,omitempty"`
	ManagedLogin          bool              `json:"managedLogin"`
	Enforcement           EnforcementState  `json:"enforcement"`
}

type RoomManifest struct {
	SchemaVersion  int       `json:"schemaVersion"`
	RoomID         string    `json:"roomId"`
	Name           string    `json:"name"`
	OwnerMemberID  string    `json:"ownerMemberId,omitempty"`
	OwnerPublicKey string    `json:"ownerPublicKey"`
	CreatedAt      time.Time `json:"createdAt"`
}

type MemberProfile struct {
	SchemaVersion      int       `json:"schemaVersion"`
	RoomID             string    `json:"roomId"`
	MemberID           string    `json:"memberId"`
	DisplayName        string    `json:"displayName"`
	PublicKey          string    `json:"publicKey"`
	JoinedAt           time.Time `json:"joinedAt,omitempty"`
	ManagedLogin       bool      `json:"managedLogin"`
	EnforcementConsent bool      `json:"enforcementConsent"`
}

type QuotaRule struct {
	LimitID        string  `json:"limitId"`
	Window         string  `json:"window"`
	MaxUsedPercent float64 `json:"maxUsedPercent"`
	Action         string  `json:"action"`
}

type MemberPolicy struct {
	MemberID            string      `json:"memberId"`
	Enabled             bool        `json:"enabled"`
	RequireManagedLogin bool        `json:"requireManagedLogin"`
	Rules               []QuotaRule `json:"rules"`
}

type Policy struct {
	SchemaVersion int                     `json:"schemaVersion"`
	RoomID        string                  `json:"roomId"`
	Revision      uint64                  `json:"revision"`
	UpdatedAt     time.Time               `json:"updatedAt"`
	Members       map[string]MemberPolicy `json:"members"`
}

type Signed[T any] struct {
	Payload   T      `json:"payload"`
	Signature string `json:"signature"`
}

type Decision struct {
	ShouldLogout bool       `json:"shouldLogout"`
	Reason       string     `json:"reason"`
	LockedUntil  time.Time  `json:"lockedUntil,omitempty"`
	Rule         *QuotaRule `json:"rule,omitempty"`
}

