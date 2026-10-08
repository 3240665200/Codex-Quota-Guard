create table official_accounts (
    id uuid primary key,
    label varchar(120) not null unique,
    estimated_total_tokens bigint not null,
    reporter_user_id uuid unique references users (id) on delete set null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint official_accounts_estimated_total_valid
        check (estimated_total_tokens >= 0)
);

alter table teams add column official_account_id uuid references official_accounts (id);
create index teams_official_account_id_idx on teams (official_account_id);
