package com.pingche.web.authenticator;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pingche.web.auth.DomainException;
import com.pingche.web.officialsession.OfficialSessionCipher;
import com.pingche.web.mail.LoginAssistanceAccess;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthenticatorService {
    public record Request(@NotNull Boolean enabled, @Size(max = 4096) String secret, @NotNull Long version) {
        @Override public String toString() { return "AuthenticatorRequest[redacted]"; }
    }
    public record Config(boolean configured, boolean encryptionReady, boolean enabled, String algorithm,
            Integer digits, Integer periodSeconds, long version) { }
    public record Availability(boolean available, String reasonCode, UUID officialAccountId, String accountLabel) { }
    public record Code(String code, Instant serverTime, Instant expiresAt, int periodSeconds, UUID officialAccountId) {
        @Override public String toString() { return "AuthenticatorCode[redacted]"; }
    }
    private record Saved(boolean enabled, String encrypted, String algorithm, int digits, int period, long version) {
        @Override public String toString() { return "AuthenticatorSaved[redacted]"; }
    }
    private final OfficialAccountRepository accounts;
    private final UserRepository users;
    private final TeamRepository teams;
    private final OfficialSessionCipher cipher;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final LoginAssistanceAccess assistance;
    private final Cache<UUID, Instant> lastCode = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(1)).build();

    public AuthenticatorService(OfficialAccountRepository accounts, UserRepository users, TeamRepository teams,
            OfficialSessionCipher cipher, JdbcTemplate jdbc, Clock clock, PlatformTransactionManager manager,
            LoginAssistanceAccess assistance) {
        this.accounts = accounts; this.users = users; this.teams = teams; this.cipher = cipher;
        this.jdbc = jdbc; this.clock = clock; tx = new TransactionTemplate(manager);
        this.assistance = assistance;
    }
    public Config read(UUID id) {
        return tx.execute(ignored -> { requireAccount(id, false); return response(saved(id)); });
    }
    public Config save(UUID id, Request request) {
        if (request.enabled == null || request.version == null) throw Totp.invalid();
        return tx.execute(ignored -> {
            requireAccount(id, true);
            Saved current = saved(id);
            long version = current == null ? 0 : current.version;
            if (request.version != version) throw error("AUTHENTICATOR_VERSION_CONFLICT", "验证器设置已变化，请重新读取。", HttpStatus.CONFLICT);
            requireEncryption();
            Saved next;
            if (request.secret == null || request.secret.isBlank()) {
                if (current == null) throw Totp.invalid();
                next = new Saved(request.enabled, current.encrypted, current.algorithm, current.digits, current.period, version + 1);
            } else {
                Totp.Provision provision = Totp.parse(request.secret);
                next = new Saved(request.enabled, cipher.encrypt(provision.secret(), aad(id)), provision.algorithm(), provision.digits(), provision.period(), version + 1);
            }
            if (current == null) jdbc.update("insert into official_account_authenticators(official_account_id,enabled,secret_cipher,algorithm,digits,period_seconds,updated_at,version) values (?,?,?,?,?,?,?,?)",
                    id, next.enabled, next.encrypted, next.algorithm, next.digits, next.period, Timestamp.from(clock.instant()), next.version);
            else jdbc.update("update official_account_authenticators set enabled=?,secret_cipher=?,algorithm=?,digits=?,period_seconds=?,updated_at=?,version=? where official_account_id=?",
                    next.enabled, next.encrypted, next.algorithm, next.digits, next.period, Timestamp.from(clock.instant()), next.version, id);
            return response(next);
        });
    }
    public void delete(UUID id) {
        tx.executeWithoutResult(ignored -> { requireAccount(id, true); jdbc.update("delete from official_account_authenticators where official_account_id=?", id); });
    }
    public Availability availability(UUID userId) {
        return availability(userId, null);
    }
    public Availability availability(UUID userId, String deviceId) {
        return tx.execute(ignored -> {
            var grant = assistance.require(userId, deviceId);
            OfficialAccount account = access(userId, false);
            if (account == null) return new Availability(false, "TEAM_UNAVAILABLE", null, null);
            if (!account.getId().equals(grant.accountId())) throw LoginAssistanceAccess.denied();
            Saved config = saved(account.getId());
            String reason = reason(config);
            return new Availability(reason == null, reason, reason == null ? account.getId() : null, reason == null ? account.getLabel() : null);
        });
    }
    public Code code(UUID userId) {
        return code(userId, null);
    }
    public Code code(UUID userId, String deviceId) {
        return tx.execute(ignored -> {
            var grant = assistance.require(userId, deviceId);
            OfficialAccount account = access(userId, false);
            if (account == null) throw error("AUTHENTICATOR_UNAVAILABLE", "所属团队或官方账户不可用。", HttpStatus.FORBIDDEN);
            if (!account.getId().equals(grant.accountId())) throw LoginAssistanceAccess.denied();
            Saved config = saved(account.getId());
            if (reason(config) != null) throw error("AUTHENTICATOR_UNAVAILABLE", "团队验证器尚未启用，请联系管理员配置。", HttpStatus.CONFLICT);
            Code code = generate(account.getId(), config, userId);
            return code.expiresAt().isAfter(grant.expiresAt())
                    ? new Code(code.code(),code.serverTime(),grant.expiresAt(),code.periodSeconds(),code.officialAccountId()) : code;
        });
    }
    public Code preview(UUID accountId, UUID administratorId) {
        return tx.execute(ignored -> {
            User administrator = users.findByIdForUpdate(administratorId).orElse(null);
            if (administrator == null || administrator.getStatus() != User.Status.ACTIVE || administrator.getRole() != User.Role.ADMIN) {
                throw error("AUTHENTICATOR_ADMIN_REQUIRED", "仅管理员可以查看官方账户验证码。", HttpStatus.FORBIDDEN);
            }
            requireAccount(accountId, true);
            requireEncryption();
            Saved config = saved(accountId);
            if (config == null) throw error("AUTHENTICATOR_UNAVAILABLE", "请先保存此账户的验证器配置。", HttpStatus.CONFLICT);
            // Administrator verification does not enable access for team members.
            return generate(accountId, config, administratorId);
        });
    }
    private Code generate(UUID accountId, Saved config, UUID requesterId) {
        Instant now = clock.instant();
        AtomicBoolean allowed = new AtomicBoolean();
        lastCode.asMap().compute(requesterId, (id, last) -> {
            if (last == null || !last.plusSeconds(1).isAfter(now)) { allowed.set(true); return now; }
            return last;
        });
        if (!allowed.get()) throw error("AUTHENTICATOR_RATE_LIMITED", "请稍后再获取验证码。", HttpStatus.TOO_MANY_REQUESTS);
        try {
            String secret = cipher.decrypt(config.encrypted, aad(accountId));
            String code = Totp.code(secret, config.algorithm, config.digits, config.period, now);
            Instant expiresAt = Instant.ofEpochSecond((now.getEpochSecond() / config.period + 1) * config.period);
            return new Code(code, now, expiresAt, config.period, accountId);
        } catch (RuntimeException ignoredCrypto) {
            throw error("AUTHENTICATOR_SECRET_UNAVAILABLE", "验证器密钥无法读取，请联系管理员重新配置。", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
    private OfficialAccount access(UUID userId, boolean lock) {
        User user = (lock ? users.findByIdForUpdate(userId) : users.findById(userId)).orElse(null);
        if (user == null || user.getStatus() != User.Status.ACTIVE || user.getTeamId() == null) return null;
        Team team = (lock ? teams.findByIdForUpdate(user.getTeamId()) : teams.findById(user.getTeamId())).orElse(null);
        if (team == null || team.getStatus() != Team.Status.ACTIVE || team.getOfficialAccountId() == null) return null;
        return (lock ? accounts.findByIdForUpdate(team.getOfficialAccountId()) : accounts.findById(team.getOfficialAccountId())).orElse(null);
    }
    private Saved saved(UUID id) {
        return jdbc.query("select enabled,secret_cipher,algorithm,digits,period_seconds,version from official_account_authenticators where official_account_id=?",
                (rs,n) -> new Saved(rs.getBoolean(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getInt(5), rs.getLong(6)), id).stream().findFirst().orElse(null);
    }
    private Config response(Saved config) {
        return config == null ? new Config(false, cipher.isReady(), false, null, null, null, 0)
                : new Config(true, cipher.isReady(), config.enabled, config.algorithm, config.digits, config.period, config.version);
    }
    private String reason(Saved config) {
        return !cipher.isReady() ? "ENCRYPTION_UNAVAILABLE" : config == null ? "AUTHENTICATOR_UNCONFIGURED" : !config.enabled ? "AUTHENTICATOR_DISABLED" : null;
    }
    private void requireEncryption() {
        if (!cipher.isReady()) throw error("AUTHENTICATOR_ENCRYPTION_UNAVAILABLE", "请配置后台官方账户加密密钥。", HttpStatus.SERVICE_UNAVAILABLE);
    }
    private void requireAccount(UUID id, boolean lock) {
        if ((lock ? accounts.findByIdForUpdate(id) : accounts.findById(id)).isEmpty()) throw error("OFFICIAL_ACCOUNT_NOT_FOUND", "官方账户不存在。", HttpStatus.NOT_FOUND);
    }
    private static String aad(UUID id) { return "authenticator:v1:" + id; }
    private static DomainException error(String code, String message, HttpStatus status) { return new DomainException(code, message, status); }
}
