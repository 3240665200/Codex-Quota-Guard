create table codex_accounts (
    id uuid primary key,
    email varchar(320) not null unique,
    display_name varchar(120) not null,
    status varchar(16) not null,
    credential_ciphertext text not null,
    credential_iv varchar(24) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint codex_accounts_email_normalized check (email = lower(trim(email))),
    constraint codex_accounts_display_name_not_blank check (length(trim(display_name)) > 0),
    constraint codex_accounts_status_valid check (status in ('ACTIVE', 'DISABLED'))
);

create index codex_accounts_created_at_idx on codex_accounts(created_at desc);
