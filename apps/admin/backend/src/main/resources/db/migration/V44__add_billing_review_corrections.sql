-- Corrections are separate from the immutable device report and its identity/ownership.
CREATE TABLE usage_billing_overrides (
    event_id VARCHAR(64) PRIMARY KEY REFERENCES usage_billing_entries(event_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL CHECK (revision > 0),
    model VARCHAR(96),
    reasoning_effort VARCHAR(24),
    total_tokens BIGINT NOT NULL CHECK (total_tokens BETWEEN 0 AND 100000000),
    input_tokens BIGINT CHECK (input_tokens BETWEEN 0 AND 100000000),
    cached_input_tokens BIGINT CHECK (cached_input_tokens BETWEEN 0 AND 100000000),
    cache_write_input_tokens BIGINT CHECK (cache_write_input_tokens BETWEEN 0 AND 100000000),
    output_tokens BIGINT CHECK (output_tokens BETWEEN 0 AND 100000000),
    reasoning_output_tokens BIGINT CHECK (reasoning_output_tokens BETWEEN 0 AND 100000000),
    pricing_mode VARCHAR(16) NOT NULL CHECK (pricing_mode IN ('CATALOG','CUSTOM')),
    input_price DECIMAL(18,6), output_price DECIMAL(18,6),
    cache_read_price DECIMAL(18,6), cache_write_price DECIMAL(18,6),
    fast_multiplier DECIMAL(12,6),
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    review_note VARCHAR(500) NOT NULL,
    CHECK (pricing_mode = 'CATALOG' OR (input_price >= 0 AND output_price >= 0
        AND cache_read_price >= 0 AND cache_write_price >= 0 AND fast_multiplier BETWEEN 1 AND 100
        AND input_price IS NOT NULL AND output_price IS NOT NULL AND cache_read_price IS NOT NULL
        AND cache_write_price IS NOT NULL AND fast_multiplier IS NOT NULL))
);

CREATE TABLE usage_billing_review_audit (
    id UUID PRIMARY KEY,
    event_id VARCHAR(64) NOT NULL REFERENCES usage_billing_entries(event_id) ON DELETE CASCADE,
    revision BIGINT NOT NULL,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    reviewed_by UUID,
    review_note VARCHAR(500) NOT NULL,
    before_state TEXT NOT NULL,
    after_state TEXT NOT NULL,
    UNIQUE(event_id,revision)
);
CREATE INDEX billing_review_audit_event_idx ON usage_billing_review_audit(event_id,revision);

-- All accounting views share corrected facts. Raw device reports remain available for audit/deduplication.
CREATE VIEW usage_effective_events AS
SELECT e.id,e.user_id,e.team_id,e.occurred_at,e.received_at,e.source,e.meter_device_id,
    CASE WHEN o.event_id IS NULL THEN e.model ELSE o.model END AS model,
    CASE WHEN o.event_id IS NULL THEN e.reasoning_effort ELSE o.reasoning_effort END AS reasoning_effort,
    CASE WHEN o.event_id IS NULL THEN e.total_tokens ELSE o.total_tokens END AS total_tokens,
    CASE WHEN o.event_id IS NULL THEN e.input_tokens ELSE o.input_tokens END AS input_tokens,
    CASE WHEN o.event_id IS NULL THEN e.cached_input_tokens ELSE o.cached_input_tokens END AS cached_input_tokens,
    CASE WHEN o.event_id IS NULL THEN e.cache_write_input_tokens ELSE o.cache_write_input_tokens END AS cache_write_input_tokens,
    CASE WHEN o.event_id IS NULL THEN e.output_tokens ELSE o.output_tokens END AS output_tokens,
    CASE WHEN o.event_id IS NULL THEN e.reasoning_output_tokens ELSE o.reasoning_output_tokens END AS reasoning_output_tokens,
    CASE b.mode_status WHEN 'FAST' THEN 'fast' WHEN 'STANDARD' THEN 'standard'
        ELSE CASE WHEN b.event_id IS NULL THEN e.service_tier ELSE 'unknown' END END AS service_tier
FROM codex_usage_events e
LEFT JOIN usage_billing_overrides o ON o.event_id=e.id
LEFT JOIN usage_billing_entries b ON b.event_id=e.id;
