alter table restriction_policy add column stale_binding_cleanup_enabled boolean not null default true;

create table stale_binding_checks (
    official_account_id uuid primary key references official_accounts(id) on delete cascade,
    next_check_at timestamp with time zone,
    last_checked_at timestamp with time zone,
    lease_id uuid,
    lease_until timestamp with time zone,
    last_error_code varchar(100),
    removed_count bigint not null default 0
);
