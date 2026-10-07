-- These token limits are independent of the upstream account's percentage windows.
-- Existing percentage policies are preserved for audit, but are not converted to
-- tokens because there is no reliable account-percent-to-token conversion.
alter table teams add column five_hour_token_limit bigint;
alter table teams add column weekly_token_limit bigint;
alter table users add column five_hour_token_limit bigint;
alter table users add column weekly_token_limit bigint;

alter table teams add constraint teams_five_hour_token_limit_valid
    check (five_hour_token_limit is null or five_hour_token_limit >= 0);
alter table teams add constraint teams_weekly_token_limit_valid
    check (weekly_token_limit is null or weekly_token_limit >= 0);
alter table users add constraint users_five_hour_token_limit_valid
    check (five_hour_token_limit is null or five_hour_token_limit >= 0);
alter table users add constraint users_weekly_token_limit_valid
    check (weekly_token_limit is null or weekly_token_limit >= 0);

create table codex_usage_events (
    id varchar(64) primary key,
    user_id uuid not null references users (id) on delete cascade,
    codex_account_id uuid not null references codex_accounts (id) on delete cascade,
    total_tokens bigint not null,
    occurred_at timestamp with time zone not null,
    received_at timestamp with time zone not null,
    source varchar(48) not null,
    constraint codex_usage_events_total_tokens_valid check (total_tokens >= 0)
);

create index codex_usage_events_user_window_idx
    on codex_usage_events (user_id, codex_account_id, received_at);
