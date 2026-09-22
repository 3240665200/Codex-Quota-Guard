alter table users
    add column session_version bigint not null default 0;

alter table users
    add constraint users_session_version_non_negative check (session_version >= 0);

alter table refresh_sessions
    add column session_version bigint not null default 0;

alter table refresh_sessions
    add constraint refresh_sessions_session_version_non_negative
        check (session_version >= 0);
