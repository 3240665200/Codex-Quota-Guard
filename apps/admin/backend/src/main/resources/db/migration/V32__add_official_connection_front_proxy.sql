-- Additive only: existing proxy_node_id remains the required exit for official traffic.
-- Rollback: deploy the previous application and leave this nullable column in place.
alter table official_account_connections add column front_proxy_node_id uuid;
alter table official_account_connections add constraint fk_official_connection_front_proxy
    foreign key (front_proxy_node_id) references managed_proxy_nodes(id);
