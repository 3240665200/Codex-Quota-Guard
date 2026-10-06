create table teams (
    id uuid primary key,
    name varchar(120) not null unique,
    description varchar(1000) not null default '',
    status varchar(16) not null,
    five_hour_quota_percent integer not null,
    weekly_quota_percent integer not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint teams_name_not_blank check (length(trim(name)) > 0),
    constraint teams_status_valid check (status in ('ACTIVE', 'DISABLED')),
    constraint teams_five_hour_quota_percent_valid
        check (five_hour_quota_percent between 0 and 100),
    constraint teams_weekly_quota_percent_valid
        check (weekly_quota_percent between 0 and 100)
);

alter table users
    add column team_id uuid;

alter table users
    add constraint users_team_id_fk
        foreign key (team_id)
        references teams (id)
        on delete set null;

alter table users
    add column five_hour_quota_percent integer;

alter table users
    add column weekly_quota_percent integer;

alter table users
    add constraint users_five_hour_quota_percent_valid
        check (five_hour_quota_percent is null
            or five_hour_quota_percent between 0 and 100);

alter table users
    add constraint users_weekly_quota_percent_valid
        check (weekly_quota_percent is null
            or weekly_quota_percent between 0 and 100);

create index users_team_id_idx on users (team_id);

create table user_quota_usage (
    user_id uuid primary key references users (id) on delete cascade,
    codex_account_id uuid not null references codex_accounts (id) on delete cascade,
    five_hour_used_percent numeric(5, 2) not null,
    weekly_used_percent numeric(5, 2) not null,
    five_hour_resets_at timestamp with time zone,
    weekly_resets_at timestamp with time zone,
    measured_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint user_quota_usage_five_hour_used_percent_valid
        check (five_hour_used_percent between 0 and 100),
    constraint user_quota_usage_weekly_used_percent_valid
        check (weekly_used_percent between 0 and 100)
);

create index user_quota_usage_codex_account_id_idx
    on user_quota_usage (codex_account_id);
