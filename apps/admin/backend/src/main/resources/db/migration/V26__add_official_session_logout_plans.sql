-- Durable single-use plans and execution fence. An expired lease never retries a write.
create table official_session_logout_plans (
    id uuid primary key,
    -- Retain the operation and fence if the originating administrator is removed.
    owner_id uuid not null,
    official_account_id uuid not null references official_accounts(id) on delete cascade,
    mode varchar(16) not null,
    status varchar(32) not null,
    connection_revision uuid not null,
    official_account_key varchar(160) not null,
    official_user_key varchar(320) not null,
    binding_fingerprint varchar(64) not null,
    session_fingerprint varchar(64) not null,
    preview_json text not null,
    results_json text,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    started_at timestamp with time zone,
    lease_until timestamp with time zone,
    acknowledged_at timestamp with time zone,
    acknowledged_by uuid,
    constraint official_logout_mode check (mode in ('ALL','UNBOUND')),
    constraint official_logout_status check (status in ('PREVIEW','EXECUTING','ACCEPTED','PARTIAL','FAILED','UNKNOWN','INVALIDATED','RESOLVED_UNKNOWN'))
);
create index official_logout_fence_idx on official_session_logout_plans(official_account_id,status);
