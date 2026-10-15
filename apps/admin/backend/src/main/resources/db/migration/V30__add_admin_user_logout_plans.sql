-- Additive, single-use user scope. Keep the audit after user/account deletion.
-- Official writes still use official_session_logout_plans and its existing fence.
create table admin_user_logout_plans (
    id uuid primary key,
    owner_id uuid not null,
    user_id uuid not null,
    status varchar(16) not null,
    scope_fingerprint varchar(64) not null,
    preview_json text not null,
    system_status varchar(16) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    started_at timestamp with time zone,
    constraint admin_user_logout_status check (status in ('PREVIEW','DISPATCHING','SUBMITTED')),
    constraint admin_user_logout_system_status check (system_status in ('NOT_ATTEMPTED','REVOKED'))
);
create index admin_user_logout_owner_idx on admin_user_logout_plans(owner_id,user_id,started_at);

create table admin_user_logout_accounts (
    plan_id uuid not null references admin_user_logout_plans(id) on delete cascade,
    official_account_id uuid not null,
    official_plan_id uuid,
    dispatch_status varchar(16) not null,
    error_code varchar(100),
    primary key (plan_id,official_account_id),
    constraint admin_user_logout_dispatch_status check (dispatch_status in ('PENDING','DISPATCHING','SUBMITTED','NOT_ATTEMPTED','UNKNOWN'))
);
