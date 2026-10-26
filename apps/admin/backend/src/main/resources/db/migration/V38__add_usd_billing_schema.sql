-- Expand only: legacy Token policies remain available for audit and rollback.
ALTER TABLE users ADD COLUMN five_hour_usd_limit DECIMAL(30, 12) CHECK (five_hour_usd_limit >= 0);
ALTER TABLE users ADD COLUMN weekly_usd_limit DECIMAL(30, 12) CHECK (weekly_usd_limit >= 0);
ALTER TABLE teams ADD COLUMN five_hour_usd_limit DECIMAL(30, 12) CHECK (five_hour_usd_limit >= 0);
ALTER TABLE teams ADD COLUMN weekly_usd_limit DECIMAL(30, 12) CHECK (weekly_usd_limit >= 0);
ALTER TABLE teams ADD COLUMN total_usd_limit DECIMAL(30, 12) CHECK (total_usd_limit >= 0);
ALTER TABLE official_accounts ADD COLUMN budget_usd DECIMAL(30, 12) CHECK (budget_usd >= 0);
ALTER TABLE official_accounts ADD COLUMN billing_reset_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE usage_model_prices ADD COLUMN fast_multiplier DECIMAL(12, 6) NOT NULL DEFAULT 1.5 CHECK (fast_multiplier >= 1 AND fast_multiplier <= 100);
ALTER TABLE codex_usage_events ADD COLUMN service_tier VARCHAR(24);
ALTER TABLE codex_usage_events ADD COLUMN meter_device_id UUID;
ALTER TABLE restriction_policy ADD COLUMN personal_interval_minutes INTEGER NOT NULL DEFAULT 1 CHECK (personal_interval_minutes BETWEEN 1 AND 1440);

CREATE TABLE usage_billing_entries (
    event_id VARCHAR(64) PRIMARY KEY REFERENCES codex_usage_events(id) ON DELETE CASCADE,
    official_account_id UUID,
    cost_usd DECIMAL(30, 12) CHECK (cost_usd >= 0),
    cost_status VARCHAR(40) NOT NULL,
    multiplier DECIMAL(12, 6) NOT NULL CHECK (multiplier >= 1),
    mode_status VARCHAR(32) NOT NULL,
    mode_source VARCHAR(32) NOT NULL DEFAULT 'UNRECORDED',
    mode_confirmed_by UUID,
    mode_confirmed_at TIMESTAMP WITH TIME ZONE,
    input_price DECIMAL(18, 6),
    output_price DECIMAL(18, 6),
    cache_read_price DECIMAL(18, 6),
    cache_write_price DECIMAL(18, 6),
    origin VARCHAR(32) NOT NULL,
    priced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CHECK ((cost_status = 'PRICED' AND cost_usd IS NOT NULL) OR (cost_status <> 'PRICED' AND cost_usd IS NULL))
);
CREATE INDEX usage_billing_account_idx ON usage_billing_entries(official_account_id, event_id);

-- Declarations apply only from their server timestamp, never retroactively.
CREATE TABLE meter_billing_modes (
    device_id UUID NOT NULL REFERENCES meter_devices(id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    effective_at TIMESTAMP WITH TIME ZONE NOT NULL,
    service_tier VARCHAR(24) NOT NULL CHECK (service_tier IN ('standard', 'fast', 'unknown')),
    declared_by UUID NOT NULL,
    PRIMARY KEY (device_id, effective_at)
);
