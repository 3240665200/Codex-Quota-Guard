-- Internal generations fence late workers; they do not attest the owner of an
-- uncontrolled upstream login. A new grant always needs a fresh baseline.
-- Forward-only additive migration: retain these columns on application rollback.
-- Do not mix old/new queue writers: old binaries do not check the generation.
alter table session_binding_accounts add column generation bigint not null default 0;
alter table session_binding_accounts add column settle_until timestamp with time zone;
alter table session_binding_accounts add column next_prepare_at timestamp with time zone;
alter table session_binding_accounts add column prepare_failures integer not null default 0;
alter table session_binding_attempts add column claim_generation bigint not null default 0;
