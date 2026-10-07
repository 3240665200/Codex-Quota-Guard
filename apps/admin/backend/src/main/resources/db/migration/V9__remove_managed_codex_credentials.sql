-- Local official sign-in replaces centrally managed Codex credentials.
-- The operator must verify a protected database backup before applying this migration.
-- Preserve per-system-user token events while severing their old account link.
drop table user_quota_usage;
alter table teams drop constraint teams_assigned_codex_account_id_fk;
drop index teams_assigned_codex_account_id_idx;
alter table teams drop column assigned_codex_account_id;
drop index codex_usage_events_user_window_idx;
alter table codex_usage_events drop column codex_account_id;
drop table codex_accounts;

create index codex_usage_events_user_received_idx
    on codex_usage_events (user_id, received_at);
