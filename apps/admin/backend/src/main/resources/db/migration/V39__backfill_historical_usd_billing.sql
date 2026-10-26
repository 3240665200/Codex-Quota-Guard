-- The upgrade-time catalog is sampled once by this statement. Existing Token
-- limits are deliberately NOT converted to dollars. Unknown speed is disclosed.
INSERT INTO usage_billing_entries(event_id,official_account_id,cost_usd,cost_status,multiplier,mode_status,
    input_price,output_price,cache_read_price,cache_write_price,origin,priced_at,mode_source)
SELECT e.id,t.official_account_id,
    CASE WHEN e.input_tokens IS NOT NULL AND e.output_tokens IS NOT NULL
        AND e.cached_input_tokens IS NOT NULL AND e.cache_write_input_tokens IS NOT NULL
        AND e.input_tokens >= 0 AND e.output_tokens >= 0 AND e.cached_input_tokens >= 0 AND e.cache_write_input_tokens >= 0
        AND e.cached_input_tokens <= e.input_tokens AND e.cache_write_input_tokens <= e.input_tokens - e.cached_input_tokens
        AND e.input_tokens + e.output_tokens = e.total_tokens
        AND (e.reasoning_output_tokens IS NULL OR (e.reasoning_output_tokens >= 0 AND e.reasoning_output_tokens <= e.output_tokens))
        AND p.model IS NOT NULL
        THEN CAST(((e.input_tokens - e.cached_input_tokens - e.cache_write_input_tokens) * p.input_price
            + e.output_tokens * p.output_price + e.cached_input_tokens * p.cache_read_price
            + e.cache_write_input_tokens * p.cache_write_price) / 1000000.0 AS DECIMAL(30,12)) ELSE NULL END,
    CASE WHEN e.input_tokens IS NULL OR e.output_tokens IS NULL OR e.cached_input_tokens IS NULL OR e.cache_write_input_tokens IS NULL THEN 'MISSING_BREAKDOWN'
        WHEN e.input_tokens < 0 OR e.output_tokens < 0 OR e.cached_input_tokens < 0 OR e.cache_write_input_tokens < 0
            OR e.cached_input_tokens > e.input_tokens OR e.cache_write_input_tokens > e.input_tokens - e.cached_input_tokens
            OR e.input_tokens + e.output_tokens <> e.total_tokens
            OR (e.reasoning_output_tokens IS NOT NULL AND (e.reasoning_output_tokens < 0 OR e.reasoning_output_tokens > e.output_tokens)) THEN 'INVALID_BREAKDOWN'
        WHEN p.model IS NULL THEN 'MISSING_PRICE' ELSE 'PRICED' END,
    1,'UNRECORDED',p.input_price,p.output_price,p.cache_read_price,p.cache_write_price,'HISTORICAL_RECALC',CURRENT_TIMESTAMP,'HISTORICAL_UNRECORDED'
FROM codex_usage_events e
LEFT JOIN teams t ON t.id=e.team_id
LEFT JOIN usage_model_prices p ON p.model=e.model;
