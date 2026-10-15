-- Additive migration. Roll back by disabling the feature; a later migration may drop this table.
-- Existing local account IDs, quotas, mailboxes and team links are not modified.
create table official_account_connections (
    official_account_id uuid primary key references official_accounts (id) on delete cascade,
    requested_account_id varchar(160) not null,
    proxy_node_id uuid references managed_proxy_nodes (id) on delete restrict,
    authorization_cipher text not null,
    cookie_cipher text,
    snapshot_cipher text,
    bound_account_id varchar(160),
    bound_user_id varchar(320),
    status varchar(32) not null,
    revision uuid not null,
    refresh_attempt uuid,
    refresh_lease_until timestamp with time zone,
    imported_at timestamp with time zone not null,
    last_attempt_at timestamp with time zone,
    last_success_at timestamp with time zone,
    last_error_code varchar(64),
    version bigint not null default 0,
    constraint official_connection_status check (status in (
        'UNVERIFIED', 'CONNECTED', 'AUTH_REQUIRED', 'ACCESS_BLOCKED', 'RATE_LIMITED', 'UNAVAILABLE', 'INVALID_RESPONSE')),
    constraint official_connection_refresh_lease check (
        (refresh_attempt is null and refresh_lease_until is null)
        or (refresh_attempt is not null and refresh_lease_until is not null))
);
create index official_connection_proxy_idx on official_account_connections (proxy_node_id);
