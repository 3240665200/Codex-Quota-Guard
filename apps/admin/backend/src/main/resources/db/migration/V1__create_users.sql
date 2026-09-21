create table users (
    id uuid primary key,
    email varchar(320) not null unique,
    password_hash varchar(100) not null,
    display_name varchar(120) not null,
    role varchar(16) not null,
    status varchar(16) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    constraint users_email_normalized check (email = lower(trim(email))),
    constraint users_display_name_not_blank check (length(trim(display_name)) > 0),
    constraint users_role_valid check (role in ('USER', 'ADMIN')),
    constraint users_status_valid check (status in ('ACTIVE', 'DISABLED'))
);
