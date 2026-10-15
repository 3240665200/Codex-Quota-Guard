-- Browser processes and credentials remain in memory; only access/fencing metadata is persisted.
alter table official_browser_login_jobs add column login_method varchar(16) not null default 'DESKTOP';
alter table official_browser_login_jobs add column owner_session_version bigint;
alter table official_browser_login_jobs add constraint browser_login_method check (login_method in ('DESKTOP','WEB'));
create index browser_login_method_owner_idx on official_browser_login_jobs(login_method,owner_id,state);
