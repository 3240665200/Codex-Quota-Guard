create table meter_devices (
    id uuid primary key,
    user_id uuid not null references users (id) on delete cascade,
    token_hash varchar(64) not null unique,
    status varchar(16) not null,
    created_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    last_seen_at timestamp with time zone,
    version bigint not null default 0,
    constraint meter_devices_status_valid check (status in ('ACTIVE', 'REVOKED'))
);

create index meter_devices_user_idx on meter_devices (user_id);
