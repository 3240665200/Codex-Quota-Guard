package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** A binding queue grant authorizes assistance, never just a signed-in user or a queued ticket. */
@Service
public class LoginAssistanceAccess {
    public static final String DEVICE_HEADER = "X-Quota-Guard-Device-ID";
    public record Grant(UUID attemptId, UUID accountId, Instant expiresAt) { }
    public record Context(boolean allowed, String reasonCode, UUID bindingAttemptId, Instant expiresAt,
            Instant serverTime, boolean mailEnabled, boolean authenticatorEnabled) { }
    private record Settings(UUID accountId, boolean mail, boolean authenticator) { }
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public LoginAssistanceAccess(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional(readOnly = true)
    public Context context(UUID userId, String deviceHeader) {
        Settings settings = settings(userId);
        UUID deviceId = device(deviceHeader);
        Instant now = clock.instant();
        if (settings == null) return new Context(false, "TEAM_UNAVAILABLE", null, null, now, false, false);
        Grant grant = deviceId == null ? null : grant(userId, deviceId, settings.accountId);
        String reason = grant != null ? null : alreadyBound(userId) ? "ALREADY_BOUND" : "BINDING_GRANT_REQUIRED";
        return new Context(grant != null, reason, grant == null ? null : grant.attemptId,
                grant == null ? null : grant.expiresAt, now, settings.mail, settings.authenticator);
    }

    /** Caller keeps this account lock until the secret/result has been projected. Binding transitions use the same lock. */
    public Grant require(UUID userId, String deviceHeader) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("ASSISTANCE_TRANSACTION_REQUIRED");
        UUID deviceId = device(deviceHeader);
        Settings before = settings(userId);
        if (deviceId == null || before == null) throw denied();
        if (jdbc.query("select id from official_accounts where id=? for update", (rs,n) -> rs.getObject(1,UUID.class), before.accountId).isEmpty())
            throw denied();
        Settings current = settings(userId);
        if (current == null || !current.accountId.equals(before.accountId)) throw denied();
        Grant grant = grant(userId, deviceId, current.accountId);
        if (grant == null) throw denied();
        return grant;
    }

    /** Background listeners use their stored attempt, not whatever new claim the same user may later acquire. */
    public boolean current(UUID userId, UUID attemptId, UUID accountId) {
        return currentGrant(userId, attemptId, accountId) != null;
    }

    public Grant currentGrant(UUID userId, UUID attemptId, UUID accountId) {
        if (attemptId == null) return null;
        List<UUID> devices = jdbc.query("select device_id from session_binding_attempts where id=? and user_id=?",
                (rs,n) -> rs.getObject(1,UUID.class), attemptId, userId);
        if (devices.isEmpty()) return null;
        Grant current = grant(userId, devices.get(0), accountId);
        return current != null && current.attemptId.equals(attemptId) ? current : null;
    }

    public boolean ownsDevice(UUID userId, UUID attemptId, String deviceHeader) {
        UUID device = device(deviceHeader);
        return device != null && attemptId != null && jdbc.queryForObject(
                "select count(*) from session_binding_attempts where id=? and user_id=? and device_id=?", Integer.class,
                attemptId, userId, device) == 1;
    }

    private Grant grant(UUID userId, UUID deviceId, UUID accountId) {
        Timestamp now = Timestamp.from(clock.instant());
        List<Grant> grants = jdbc.query("""
                select a.id,a.official_account_id,a.expires_at from session_binding_attempts a
                join users u on u.id=a.user_id and u.status='ACTIVE' and u.team_id=a.team_id
                join teams t on t.id=a.team_id and t.status='ACTIVE' and t.version=a.team_version
                    and t.official_account_id=a.official_account_id
                join meter_devices d on d.id=a.device_id and d.id=a.enrollment_id
                    and d.user_id=u.id and d.status='ACTIVE' and d.expires_at>?
                join session_binding_accounts g on g.official_account_id=a.official_account_id
                    and g.active_attempt_id=a.id and g.generation=a.claim_generation
                    and g.quarantined=false and g.recovery_id is null
                    and (g.settle_until is null or g.settle_until<=?)
                join official_account_connections c on c.official_account_id=a.official_account_id
                    and c.revision=a.connection_revision and c.status in ('CONNECTED','UNVERIFIED','UNAVAILABLE','RATE_LIMITED')
                    and c.requested_account_id=c.bound_account_id and c.bound_account_id=a.official_account_key
                    and c.bound_user_id=a.official_user_key and length(trim(c.bound_user_id))>0
                where a.user_id=? and a.device_id=? and a.official_account_id=? and a.expires_at>?
                    and a.status in ('READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')
                    and a.before_session_ids is not null
                    and not exists (select 1 from session_bindings b where b.status='ACTIVE'
                        and (b.user_id=u.id or b.active_device_id=d.id))
                    and not exists (select 1 from official_session_logout_plans p
                        where p.official_account_id=a.official_account_id and p.status in ('EXECUTING','UNKNOWN'))
                    and (select count(*) from official_account_connections other
                        where other.requested_account_id=c.requested_account_id)=1
                """, (rs,n) -> new Grant(rs.getObject("id",UUID.class), rs.getObject("official_account_id",UUID.class),
                        rs.getTimestamp("expires_at").toInstant()), now, now, userId, deviceId, accountId, now);
        return grants.size() == 1 ? grants.get(0) : null;
    }

    private Settings settings(UUID userId) {
        return jdbc.query("""
                select a.id,coalesce(m.enabled,false) as mail,coalesce(v.enabled,false) as authenticator
                from users u join teams t on t.id=u.team_id and t.status='ACTIVE'
                join official_accounts a on a.id=t.official_account_id
                left join official_account_mailboxes m on m.official_account_id=a.id
                left join official_account_authenticators v on v.official_account_id=a.id
                where u.id=? and u.status='ACTIVE'
                """, (rs,n) -> new Settings(rs.getObject(1,UUID.class),rs.getBoolean(2),rs.getBoolean(3)), userId)
                .stream().findFirst().orElse(null);
    }

    private boolean alreadyBound(UUID userId) {
        return jdbc.queryForObject("select count(*) from session_bindings where user_id=? and status='ACTIVE'", Integer.class, userId)>0;
    }
    private UUID device(String value) {
        try { UUID id=UUID.fromString(value); return id.toString().equalsIgnoreCase(value) ? id : null; }
        catch (IllegalArgumentException | NullPointerException ignored) { return null; }
    }
    public static DomainException denied() {
        return new DomainException("LOGIN_ASSISTANCE_NOT_ALLOWED", "登录辅助仅在取得本次绑定名额后开放，绑定完成后自动关闭。", HttpStatus.FORBIDDEN);
    }
}
