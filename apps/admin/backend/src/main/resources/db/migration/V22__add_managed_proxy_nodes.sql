-- Additive migration. Roll back by disabling the feature; remove its tables only in a later forward migration.
create table managed_proxy_nodes (
    id uuid primary key,
    label varchar(120) not null,
    protocol varchar(24) not null,
    host varchar(253) not null,
    port integer not null,
    config_cipher text not null,
    has_credentials boolean not null,
    enabled boolean not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint managed_proxy_node_port check (port between 1 and 65535),
    constraint managed_proxy_node_protocol check (protocol in ('HTTP','HTTPS','SOCKS5','VLESS','VMESS','TROJAN','SHADOWSOCKS'))
);
create index managed_proxy_node_label_idx on managed_proxy_nodes (label, id);
