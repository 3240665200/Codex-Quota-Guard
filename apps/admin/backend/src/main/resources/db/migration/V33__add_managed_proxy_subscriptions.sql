-- Additive only. Roll back application use by disabling subscription management; retain encrypted URLs and node references.
-- To remove the schema later: detach source references first, then drop these new columns/table in a forward migration.
create table managed_proxy_subscriptions (
    id uuid primary key,
    label varchar(120) not null,
    url_cipher text not null,
    source_host varchar(253) not null,
    status varchar(24) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    refreshed_at timestamp with time zone not null,
    version bigint not null default 0
);
alter table managed_proxy_nodes add column source_subscription_id uuid;
alter table managed_proxy_nodes add column source_node_key varchar(120);
alter table managed_proxy_nodes add column source_missing boolean not null default false;
alter table managed_proxy_nodes add constraint managed_proxy_node_subscription_fk
    foreign key (source_subscription_id) references managed_proxy_subscriptions(id);
alter table managed_proxy_nodes add constraint managed_proxy_node_source_unique
    unique (source_subscription_id, source_node_key);
