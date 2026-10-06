alter table users
    add column assigned_codex_account_id uuid;

alter table users
    add constraint users_assigned_codex_account_id_fk
        foreign key (assigned_codex_account_id)
        references codex_accounts (id)
        on delete set null;

create index users_assigned_codex_account_id_idx
    on users (assigned_codex_account_id);
