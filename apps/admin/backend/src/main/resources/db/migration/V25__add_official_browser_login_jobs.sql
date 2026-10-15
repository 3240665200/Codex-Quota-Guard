-- Only coordination metadata is stored here, never browser credentials.
create table official_browser_login_jobs (
    id uuid primary key,
    owner_id uuid not null references users(id) on delete cascade,
    official_account_id uuid not null references official_accounts(id) on delete cascade,
    account_name varchar(120) not null,
    proxy_node_id uuid references managed_proxy_nodes(id) on delete restrict,
    connection_version bigint not null,
    state varchar(32) not null,
    helper_id uuid,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null,
    error_code varchar(64),
    constraint browser_login_state check (state in ('WAITING_FOR_HELPER','WAITING_FOR_LOGIN','SAVING','COMPLETED','FAILED','CANCELLED','EXPIRED'))
);
create index browser_login_owner_idx on official_browser_login_jobs(owner_id, created_at);
create index browser_login_account_idx on official_browser_login_jobs(official_account_id, state);
