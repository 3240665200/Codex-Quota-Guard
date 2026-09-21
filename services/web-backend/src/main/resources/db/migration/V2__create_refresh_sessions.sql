create table refresh_sessions (
    id uuid primary key,
    user_id uuid not null references users(id) on delete cascade,
    family_id uuid not null,
    token_hash varchar(64) not null unique,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    replaced_by_id uuid,
    created_at timestamp with time zone not null,
    version bigint not null default 0,
    constraint refresh_sessions_replacement_fk
        foreign key (replaced_by_id) references refresh_sessions(id)
);

create index refresh_sessions_user_id_idx on refresh_sessions(user_id);
create index refresh_sessions_family_id_idx on refresh_sessions(family_id);
create index refresh_sessions_expires_at_idx on refresh_sessions(expires_at);
