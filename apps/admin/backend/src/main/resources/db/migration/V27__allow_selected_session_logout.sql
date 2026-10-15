alter table official_session_logout_plans drop constraint official_logout_mode;
alter table official_session_logout_plans add constraint official_logout_mode check (mode in ('ALL','UNBOUND','SELECTED'));
