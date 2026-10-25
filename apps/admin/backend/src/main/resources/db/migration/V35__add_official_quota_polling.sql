-- Quota percentages come from the official account API, independently of desktop reports.
create table official_account_quota_polls (
    official_account_id uuid primary key references official_accounts(id) on delete cascade,
    enabled boolean not null default true,
    interval_seconds integer not null default 300 check (interval_seconds between 60 and 86400),
    status varchar(32) not null default 'PENDING',
    last_attempt_at timestamp with time zone,
    last_success_at timestamp with time zone,
    next_attempt_at timestamp with time zone,
    failure_count integer not null default 0,
    last_error_code varchar(64),
    snapshot_json text,
    connection_revision uuid,
    attempt_id uuid,
    lease_until timestamp with time zone,
    paused boolean not null default false,
    version bigint not null default 0
);
create index official_quota_due_idx on official_account_quota_polls(enabled, next_attempt_at);
