-- Additive migration. Roll back by disabling the feature; a later migration may drop these tables.
create table official_account_mailboxes (
    official_account_id uuid primary key references official_accounts (id) on delete cascade,
    enabled boolean not null,
    provider varchar(32) not null,
    host varchar(253) not null,
    port integer not null,
    username varchar(320) not null,
    recipient_email varchar(320) not null unique,
    folder varchar(200) not null,
    sender_email varchar(320) not null,
    auth_mode varchar(32) not null,
    oauth_client_id varchar(320),
    oauth_tenant varchar(100),
    password_cipher text,
    client_secret_cipher text,
    refresh_token_cipher text,
    connection_test_until timestamp with time zone,
    mailbox_key varchar(64) not null unique,
    revision uuid not null,
    version bigint not null default 0,
    constraint official_account_mailbox_port check (port between 1 and 65535),
    constraint official_account_mailbox_auth check (auth_mode in ('PASSWORD','GOOGLE_OAUTH2','MICROSOFT_OAUTH2'))
);

create table mail_login_listeners (
    id uuid primary key,
    official_account_id uuid not null references official_accounts (id) on delete cascade,
    owner_user_id uuid not null references users (id) on delete cascade,
    team_id uuid not null,
    config_revision uuid not null,
    active_account_id uuid unique references official_accounts (id) on delete cascade,
    state varchar(16) not null,
    started_at timestamp with time zone not null,
    expires_at timestamp with time zone not null,
    listening_at timestamp with time zone,
    received_at timestamp with time zone,
    result_expires_at timestamp with time zone,
    code_cipher text,
    error_code varchar(64),
    uid_validity bigint,
    next_uid bigint,
    version bigint not null default 0,
    constraint mail_login_listener_state check (state in ('CONNECTING','LISTENING','RECEIVED','CANCELLED','TIMED_OUT','FAILED')),
    constraint mail_login_listener_active check (
      (state in ('CONNECTING','LISTENING') and active_account_id is not null and active_account_id = official_account_id)
      or (state not in ('CONNECTING','LISTENING') and active_account_id is null))
);
create index mail_login_listener_owner_started_idx on mail_login_listeners (owner_user_id, started_at);
create index mail_login_listener_expiry_idx on mail_login_listeners (expires_at);
create index mail_login_listener_result_expiry_idx on mail_login_listeners (result_expires_at);
