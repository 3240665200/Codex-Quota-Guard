-- Additive metadata only. Rollback: deploy the earlier application and leave these nullable/defaulted columns.
-- No existing proxy configuration or credentials are changed.
alter table managed_proxy_nodes add column egress_ip varchar(45);
alter table managed_proxy_nodes add column country varchar(120);
alter table managed_proxy_nodes add column region varchar(120);
alter table managed_proxy_nodes add column city varchar(120);
alter table managed_proxy_nodes add column tested_at timestamp with time zone;
alter table managed_proxy_nodes add column test_status varchar(24) not null default 'UNTESTED';
