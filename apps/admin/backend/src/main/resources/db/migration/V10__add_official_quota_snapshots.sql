create table official_quota_snapshots (
    user_id uuid primary key references users (id) on delete cascade,
    plan_type varchar(40),
    windows_json varchar(8192) not null,
    fetched_at timestamp with time zone not null,
    received_at timestamp with time zone not null
);
