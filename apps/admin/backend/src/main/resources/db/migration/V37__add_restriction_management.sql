create table restriction_policy (
    id integer primary key check (id = 1),
    personal_quota_logout_enabled boolean not null default false,
    unbound_cleanup_enabled boolean not null default false,
    unbound_interval_minutes integer not null default 90 check (unbound_interval_minutes between 30 and 1440),
    version bigint not null default 0,
    updated_by uuid,
    updated_at timestamp with time zone not null
);
insert into restriction_policy(id,updated_at) values (1,current_timestamp);

create table restriction_account_checks (
    official_account_id uuid primary key references official_accounts(id) on delete cascade,
    quota_checked_at timestamp with time zone,
    unbound_checked_at timestamp with time zone,
    next_quota_at timestamp with time zone,
    next_unbound_at timestamp with time zone,
    lease_id uuid,
    lease_until timestamp with time zone,
    cooldown_until timestamp with time zone,
    pending_plan_id uuid references official_session_logout_plans(id) on delete set null,
    last_error_code varchar(100)
);

create table restriction_executions (
    id uuid primary key,
    official_account_id uuid references official_accounts(id) on delete set null,
    account_label varchar(320) not null,
    rule varchar(32) not null check (rule in ('PERSONAL_QUOTA','UNBOUND')),
    checked_at timestamp with time zone not null,
    policy_version bigint not null,
    plan_id uuid references official_session_logout_plans(id) on delete set null,
    status varchar(32) not null,
    targeted_count integer not null,
    excluded_count integer not null,
    error_code varchar(100)
);
create index restriction_executions_recent_idx on restriction_executions(checked_at,id);
