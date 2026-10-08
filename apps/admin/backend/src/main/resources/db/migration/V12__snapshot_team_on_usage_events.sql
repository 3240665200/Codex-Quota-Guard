-- Attribute a reported event to the team at acceptance time. Existing events
-- can only be attributed to their users' current team as a best-effort backfill.
alter table codex_usage_events add column team_id uuid;
update codex_usage_events event
set team_id = (select users.team_id from users where users.id = event.user_id);
create index codex_usage_events_team_received_idx
    on codex_usage_events (team_id, received_at);
