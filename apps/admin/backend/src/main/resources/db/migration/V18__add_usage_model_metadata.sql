-- Additive metadata only; existing events and quota calculations are unchanged.
-- Application rollback can leave these nullable columns in place safely.
ALTER TABLE codex_usage_events ADD COLUMN model VARCHAR(96);
ALTER TABLE codex_usage_events ADD COLUMN reasoning_effort VARCHAR(24);
