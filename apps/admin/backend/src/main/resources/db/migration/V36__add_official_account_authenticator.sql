-- The provisioning secret is write-only and encrypted with account-specific authenticated data.
create table official_account_authenticators (
    official_account_id uuid primary key references official_accounts(id) on delete cascade,
    enabled boolean not null,
    secret_cipher text not null,
    algorithm varchar(8) not null,
    digits integer not null check (digits in (6,8)),
    period_seconds integer not null check (period_seconds between 15 and 120),
    updated_at timestamp with time zone not null,
    version bigint not null default 0
);
