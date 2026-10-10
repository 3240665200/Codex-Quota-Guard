-- CC Switch OpenAI/Codex standard, short-context USD prices per 1M tokens.
-- Snapshot: https://github.com/farion1231/cc-switch/blob/eda410e57b4659d6bbb2a10a069d8bac2213099a/src-tauri/src/database/schema.rs
-- Checked on 2026-10-10. These are estimates, not Codex subscription charges.
-- Existing administrator prices win; later administrator edits become MANUAL.
ALTER TABLE usage_model_prices ADD COLUMN price_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE usage_model_prices ADD COLUMN source_reference VARCHAR(512);
ALTER TABLE usage_model_prices ADD COLUMN source_checked_at DATE;

INSERT INTO usage_model_prices (
    model, input_price, output_price, cache_read_price, cache_write_price,
    updated_at, price_source, source_reference, source_checked_at
)
SELECT seed.model, seed.input_price, seed.output_price, seed.cache_read_price,
       seed.cache_write_price, CURRENT_TIMESTAMP, 'CC_SWITCH',
       'https://github.com/farion1231/cc-switch/blob/eda410e57b4659d6bbb2a10a069d8bac2213099a/src-tauri/src/database/schema.rs',
       DATE '2026-10-10'
FROM (VALUES
    ('gpt-6-astra', 10, 50, 1, 12.5),
    ('gpt-6.1-sol', 2, 10, 0.10, 2.50),
    ('gpt-6-sol', 2, 10, 0.20, 2.50),
    ('gpt-6-luna', 0.10, 0.50, 0.01, 0.125),
    ('gpt-5.6-sol', 4, 20, 0.40, 5),
    ('gpt-5.6-terra', 2, 12, 0.20, 2.50),
    ('gpt-5.6-luna', 0.20, 1.20, 0.02, 0.25),
    ('gpt-5.6-cyber', 12.50, 75, 1.25, 15.625),
    ('gpt-5.6', 4, 20, 0.40, 5),
    ('gpt-5.6-low', 4, 20, 0.40, 5),
    ('gpt-5.6-medium', 4, 20, 0.40, 5),
    ('gpt-5.6-high', 4, 20, 0.40, 5),
    ('gpt-5.6-xhigh', 4, 20, 0.40, 5),
    ('gpt-5.6-minimal', 4, 20, 0.40, 5),
    ('gpt-5.5', 5, 30, 0.50, 0),
    ('gpt-5.5-low', 5, 30, 0.50, 0),
    ('gpt-5.5-medium', 5, 30, 0.50, 0),
    ('gpt-5.5-high', 5, 30, 0.50, 0),
    ('gpt-5.5-xhigh', 5, 30, 0.50, 0),
    ('gpt-5.5-minimal', 5, 30, 0.50, 0),
    ('gpt-5.4', 2.50, 15, 0.25, 0),
    ('gpt-5.4-mini', 0.75, 4.50, 0.075, 0),
    ('gpt-5.4-nano', 0.20, 1.25, 0.02, 0),
    ('gpt-5.3-codex', 1.75, 14, 0.175, 0),
    ('gpt-5.3-codex-spark', 1.75, 14, 0.175, 0),
    ('gpt-5.3-codex-low', 1.75, 14, 0.175, 0),
    ('gpt-5.3-codex-medium', 1.75, 14, 0.175, 0),
    ('gpt-5.3-codex-high', 1.75, 14, 0.175, 0),
    ('gpt-5.3-codex-xhigh', 1.75, 14, 0.175, 0),
    ('gpt-5.2', 1.75, 14, 0.175, 0),
    ('gpt-5.2-codex', 1.75, 14, 0.175, 0),
    ('gpt-5.2-codex-low', 1.75, 14, 0.175, 0),
    ('gpt-5.2-codex-medium', 1.75, 14, 0.175, 0),
    ('gpt-5.2-codex-high', 1.75, 14, 0.175, 0),
    ('gpt-5.2-codex-xhigh', 1.75, 14, 0.175, 0),
    ('gpt-5.1', 1.25, 10, 0.125, 0),
    ('gpt-5.1-codex', 1.25, 10, 0.125, 0),
    ('gpt-5.1-codex-mini', 1.25, 10, 0.125, 0),
    ('gpt-5.1-codex-max', 1.25, 10, 0.125, 0),
    ('gpt-5', 1.25, 10, 0.125, 0),
    ('gpt-5-codex', 1.25, 10, 0.125, 0),
    ('gpt-5-codex-mini', 1.25, 10, 0.125, 0),
    ('gpt-5-mini', 0.25, 2, 0.025, 0),
    ('gpt-5-nano', 0.05, 0.40, 0.005, 0),
    ('codex-mini', 0.75, 3, 0.025, 0),
    ('o3', 2, 8, 0.50, 0),
    ('o4-mini', 1.10, 4.40, 0.275, 0),
    ('gpt-4.1', 2, 8, 0.50, 0),
    ('gpt-4.1-mini', 0.40, 1.60, 0.10, 0),
    ('gpt-4.1-nano', 0.10, 0.40, 0.025, 0),
    ('gpt-4o', 2.50, 10, 1.25, 0),
    ('gpt-4o-mini', 0.15, 0.60, 0.075, 0)
) AS seed(model, input_price, output_price, cache_read_price, cache_write_price)
WHERE NOT EXISTS (SELECT 1 FROM usage_model_prices existing WHERE existing.model = seed.model);
