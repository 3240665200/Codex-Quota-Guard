package com.pingche.web.mail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** DB metadata only: never refreshes an official session or polls an IMAP connection. */
@Component
public class LoginAssistanceState {
    private final JdbcTemplate jdbc;
    public LoginAssistanceState(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(readOnly = true)
    public String fingerprint(UUID userId) {
        var attempt = jdbc.queryForList("""
                select a.id,a.status,a.error_code,a.expires_at,a.candidate_fetched_at,a.claim_generation,
                    g.active_attempt_id,g.quarantined,g.reason,g.updated_at,g.generation,
                    g.settle_until,g.next_prepare_at,g.prepare_failures,
                    (select count(*) from session_binding_attempts earlier
                        where earlier.official_account_id=a.official_account_id and earlier.sequence_id<a.sequence_id
                        and earlier.status in ('QUEUED','PREPARING','READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')) as ahead
                from session_binding_attempts a left join session_binding_accounts g
                    on g.official_account_id=a.official_account_id
                where a.user_id=? order by a.sequence_id desc limit 1
                """, userId);
        var binding = jdbc.queryForList("""
                select id,status,ended_at from session_bindings where user_id=? order by created_at desc,id desc limit 1
                """, userId);
        var mail = jdbc.queryForList("""
                select id,version,state,expires_at from mail_login_listeners
                where owner_user_id=? order by started_at desc,id desc limit 1
                """, userId);
        var context = jdbc.queryForList("""
                select u.team_id,t.version,t.status,m.revision,m.enabled,v.version as authenticator_version,v.enabled as authenticator_enabled,
                    c.revision as connection_revision,c.status as connection_status
                from users u left join teams t on t.id=u.team_id
                left join official_account_mailboxes m on m.official_account_id=t.official_account_id
                left join official_account_authenticators v on v.official_account_id=t.official_account_id
                left join official_account_connections c on c.official_account_id=t.official_account_id
                where u.id=?
                """, userId);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (attempt.toString() + binding + mail + context).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("State fingerprint is unavailable"); }
    }
}
