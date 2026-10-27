alter table mail_login_listeners add column binding_attempt_id uuid references session_binding_attempts(id) on delete set null;
-- Previous listeners have no verified claim scope and must not survive this security change.
update mail_login_listeners set state='CANCELLED',active_account_id=null,code_cipher=null,
    result_expires_at=null,received_at=null,error_code='MAIL_ACCESS_REVOKED'
    where state in ('CONNECTING','LISTENING','RECEIVED');
