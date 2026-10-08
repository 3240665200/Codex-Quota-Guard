alter table users add column quota_reset_at timestamp with time zone;
alter table teams add column quota_reset_at timestamp with time zone;
