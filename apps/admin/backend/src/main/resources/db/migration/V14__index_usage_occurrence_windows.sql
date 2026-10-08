-- Rolling quota windows use the validated occurrence time. Receipt time remains
-- available for audit ordering and last-report diagnostics.
create index codex_usage_events_user_occurred_idx
    on codex_usage_events (user_id, occurred_at);
create index codex_usage_events_team_occurred_idx
    on codex_usage_events (team_id, occurred_at);
