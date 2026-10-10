-- Additive migration: old records retain NULL breakdown, never fabricated zeros.
-- Rollback: run the previous application and leave these unused columns/table in place.
ALTER TABLE codex_usage_events ADD COLUMN input_tokens BIGINT CHECK (input_tokens >= 0);
ALTER TABLE codex_usage_events ADD COLUMN cached_input_tokens BIGINT CHECK (cached_input_tokens >= 0);
ALTER TABLE codex_usage_events ADD COLUMN cache_write_input_tokens BIGINT CHECK (cache_write_input_tokens >= 0);
ALTER TABLE codex_usage_events ADD COLUMN output_tokens BIGINT CHECK (output_tokens >= 0);
ALTER TABLE codex_usage_events ADD COLUMN reasoning_output_tokens BIGINT CHECK (reasoning_output_tokens >= 0);

CREATE TABLE usage_model_prices (
    model VARCHAR(96) PRIMARY KEY,
    input_price DECIMAL(18, 6) NOT NULL CHECK (input_price >= 0),
    output_price DECIMAL(18, 6) NOT NULL CHECK (output_price >= 0),
    cache_read_price DECIMAL(18, 6) NOT NULL CHECK (cache_read_price >= 0),
    cache_write_price DECIMAL(18, 6) NOT NULL CHECK (cache_write_price >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);
