package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.quota.OfficialQuotaSnapshotResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Database leases and persisted cooldowns cover both scheduled and manual requests, across replicas. */
@Service
public class OfficialQuotaPollingService {
    public record Settings(@NotNull Boolean enabled, @Min(60) @Max(86400) int intervalSeconds, @NotNull Long version) { }
    public record Response(boolean enabled, int intervalSeconds, String status, Instant lastAttemptAt,
            Instant lastSuccessAt, Instant nextAttemptAt, String lastErrorCode, long version,
            OfficialQuotaSnapshotResponse quota) { }
    private record State(boolean enabled, int interval, String status, Instant attempted, Instant succeeded,
            Instant next, int failures, String error, String snapshot, UUID revision, UUID attempt,
            Instant lease, boolean paused, long version) { }
    private record Work(UUID accountId, UUID revision, UUID attempt, String requestedId, String userId,
            UUID front, UUID exit, String authorization, String cookie) {
        String aad(String field) { return "official-session:v1:" + accountId + ":" + field; }
        @Override public String toString() { return "OfficialQuotaWork[redacted]"; }
    }
    private final OfficialAccountRepository accounts;
    private final OfficialAccountConnectionRepository connections;
    private final OfficialSessionCipher cipher;
    private final OfficialSessionClient client;
    private final OfficialSessionLogoutFence fence;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OfficialQuotaPollingService(OfficialAccountRepository accounts, OfficialAccountConnectionRepository connections,
            OfficialSessionCipher cipher, OfficialSessionClient client, OfficialSessionLogoutFence fence,
            JdbcTemplate jdbc, ObjectMapper mapper, Clock clock, PlatformTransactionManager manager) {
        this.accounts = accounts; this.connections = connections; this.cipher = cipher; this.client = client;
        this.fence = fence; this.jdbc = jdbc; this.mapper = mapper; this.clock = clock;
        tx = new TransactionTemplate(manager);
    }

    public Response read(UUID id) {
        return tx.execute(ignored -> {
            requireAccount(id, false);
            State state = state(id);
            OfficialAccountConnection connection = connections.findById(id).orElse(null);
            boolean current = state != null && connection != null && Objects.equals(state.revision, connection.revision);
            OfficialQuotaSnapshotResponse quota = null;
            if (current && state.snapshot != null) {
                try { quota = mapper.readValue(state.snapshot, OfficialQuotaSnapshotResponse.class); }
                catch (RuntimeException ignoredParse) { /* Never fabricate a zero-percent quota. */ }
            }
            String status;
            if (state != null && !state.enabled) status = "DISABLED";
            else if (connection == null || connection.boundAccountId == null) status = "AUTH_REQUIRED";
            else if (!current || "DISABLED".equals(state.status)) status = "PENDING";
            else status = state.paused ? "AUTH_REQUIRED" : state.status;
            return new Response(state == null || state.enabled, state == null ? 300 : state.interval, status,
                    state == null ? null : state.attempted, current ? state.succeeded : null,
                    state == null ? null : state.next, current ? state.error : null,
                    state == null ? 0 : state.version, quota);
        });
    }

    public Response configure(UUID id, Settings settings) {
        if (settings.enabled == null || settings.version == null || settings.intervalSeconds < 60 || settings.intervalSeconds > 86400)
            throw error("OFFICIAL_QUOTA_SETTINGS_INVALID", "检测间隔应为 60～86400 秒。", HttpStatus.BAD_REQUEST);
        tx.executeWithoutResult(ignored -> {
            requireAccount(id, true);
            State state = ensureState(id);
            if (settings.version != state.version) throw error("OFFICIAL_QUOTA_VERSION_CONFLICT", "检测设置已变化，请重新读取。", HttpStatus.CONFLICT);
            // Editing settings does not clear a 429 cooldown or an in-flight lease.
            Instant next = state.next;
            if (state.attempted != null) next = later(next, state.attempted.plusSeconds(settings.intervalSeconds));
            jdbc.update("update official_account_quota_polls set enabled=?, interval_seconds=?, next_attempt_at=?, version=version+1 where official_account_id=?",
                    settings.enabled, settings.intervalSeconds, ts(next), id);
        });
        return read(id);
    }

    public Response refresh(UUID id) {
        Work work = reserve(id, true);
        execute(work);
        return read(id);
    }

    void refreshWhenDue(UUID id) {
        Work work = reserve(id, false);
        if (work != null) execute(work);
    }

    List<UUID> dueAccounts() {
        Timestamp now = ts(clock.instant());
        return jdbc.query("select c.official_account_id from official_account_connections c left join official_account_quota_polls q "
                + "on q.official_account_id=c.official_account_id where c.bound_account_id is not null "
                + "and (q.official_account_id is null or (q.enabled=true and (q.next_attempt_at is null or q.next_attempt_at<=?) "
                + "and (q.lease_until is null or q.lease_until<=?) and (q.paused=false or q.connection_revision<>c.revision))) "
                + "order by q.next_attempt_at nulls first, c.official_account_id limit 32", (rs, n) -> rs.getObject(1, UUID.class), now, now);
    }

    private Work reserve(UUID id, boolean manual) {
        return tx.execute(ignored -> {
            requireAccount(id, true);
            fence.assertAvailable(id);
            OfficialAccountConnection connection = connections.findByIdForUpdate(id).orElse(null);
            if (connection == null || connection.boundAccountId == null || connection.boundUserId == null
                    || !connection.requestedAccountId.equals(connection.boundAccountId)) {
                if (manual) throw error("OFFICIAL_QUOTA_AUTH_REQUIRED", "请先完成该账户的授权登录并验证身份。", HttpStatus.CONFLICT);
                return null;
            }
            State state = ensureState(id);
            Instant now = clock.instant();
            if (!state.enabled) {
                if (manual) throw error("OFFICIAL_QUOTA_DISABLED", "请先启用额度检测。", HttpStatus.CONFLICT);
                return null;
            }
            if (state.lease != null && state.lease.isAfter(now) || state.next != null && state.next.isAfter(now)) {
                if (manual) throw error("OFFICIAL_QUOTA_COOLDOWN", "尚在检测冷却时间内，请等到下次检测时间。", HttpStatus.TOO_MANY_REQUESTS);
                return null;
            }
            if (state.paused && Objects.equals(state.revision, connection.revision)) {
                if (manual) throw error("OFFICIAL_QUOTA_REAUTHORIZE", "官方授权需重新验证，请更新授权后重试。", HttpStatus.CONFLICT);
                return null;
            }
            UUID attempt = UUID.randomUUID();
            boolean changed = !Objects.equals(state.revision, connection.revision);
            jdbc.update("update official_account_quota_polls set attempt_id=?, lease_until=?, last_attempt_at=?, next_attempt_at=?, "
                    + "status='FETCHING', connection_revision=?, paused=false, snapshot_json=?, last_success_at=?, failure_count=? where official_account_id=?",
                    attempt, ts(now.plusSeconds(90)), ts(now), ts(now.plusSeconds(state.interval)), connection.revision,
                    changed ? null : state.snapshot, changed ? null : ts(state.succeeded), changed ? 0 : state.failures, id);
            return new Work(id, connection.revision, attempt, connection.requestedAccountId, connection.boundUserId,
                    connection.frontProxyNodeId, connection.proxyNodeId, connection.authorizationCipher, connection.cookieCipher);
        });
    }

    private void execute(Work work) {
        OfficialQuotaSnapshotResponse result = null;
        String failure = null;
        Instant retryAt = null;
        try {
            OfficialSessionCredentials credentials = new OfficialSessionCredentials(cipher.decrypt(work.authorization, work.aad("authorization")),
                    work.cookie == null ? null : cipher.decrypt(work.cookie, work.aad("cookie")), work.requestedId);
            result = client.fetchQuota(credentials, work.front, work.exit, work.userId, clock.instant());
            if (result == null) failure = "OFFICIAL_RESPONSE_INVALID";
        } catch (OfficialRateLimitedException limited) { failure = limited.getCode(); retryAt = limited.retryAt(); }
        catch (DomainException rejected) { failure = safeCode(rejected.getCode()); }
        catch (RuntimeException ignored) { failure = "OFFICIAL_UNAVAILABLE"; }
        complete(work, result, failure, retryAt);
    }

    private void complete(Work work, OfficialQuotaSnapshotResponse quota, String failure, Instant retryAt) {
        tx.executeWithoutResult(ignored -> {
            // The account lock also serializes deletion, authorization replacement and configuration changes.
            if (accounts.findByIdForUpdate(work.accountId).isEmpty()) return;
            OfficialAccountConnection connection = connections.findByIdForUpdate(work.accountId).orElse(null);
            State state = state(work.accountId);
            if (state == null || connection == null || !Objects.equals(connection.revision, work.revision)
                    || !Objects.equals(state.attempt, work.attempt)) return;
            if (!state.enabled) {
                jdbc.update("update official_account_quota_polls set attempt_id=null, lease_until=null, status='DISABLED' where official_account_id=?", work.accountId);
                return;
            }
            Instant now = clock.instant();
            boolean success = failure == null && quota != null;
            int failures = success ? 0 : Math.min(20, state.failures + 1);
            long delay = success ? state.interval : Math.max(state.interval, Math.min(86400, 300L << Math.min(8, failures - 1)));
            // A small stable per-account offset avoids synchronized retries after an upstream outage.
            if (!success) delay += Math.floorMod(work.accountId.hashCode(), 31);
            Instant next = later(state.next, later(now.plusSeconds(delay), retryAt));
            boolean paused = !success && List.of("OFFICIAL_AUTH_REQUIRED", "OFFICIAL_ACCESS_BLOCKED", "OFFICIAL_CHALLENGE_REQUIRED",
                    "OFFICIAL_ACCOUNT_MISMATCH", "OFFICIAL_CREDENTIALS_UNAVAILABLE", "OFFICIAL_ENCRYPTION_UNAVAILABLE").contains(failure);
            jdbc.update("update official_account_quota_polls set status=?, last_success_at=?, next_attempt_at=?, failure_count=?, "
                    + "last_error_code=?, snapshot_json=?, attempt_id=null, lease_until=null, paused=? where official_account_id=?",
                    success ? "SUCCESS" : paused ? "AUTH_REQUIRED" : "RETRY_WAIT", success ? ts(now) : ts(state.succeeded), ts(next), failures,
                    success ? null : failure, success ? mapper.writeValueAsString(quota) : state.snapshot, paused, work.accountId);
        });
    }

    private String safeCode(String code) {
        return List.of("OFFICIAL_AUTH_REQUIRED", "OFFICIAL_ACCESS_BLOCKED", "OFFICIAL_CHALLENGE_REQUIRED", "OFFICIAL_ACCOUNT_MISMATCH",
                "OFFICIAL_RESPONSE_INVALID", "OFFICIAL_PROXY_REQUIRED", "OFFICIAL_PROXY_UNAVAILABLE", "OFFICIAL_CREDENTIALS_UNAVAILABLE",
                "OFFICIAL_ENCRYPTION_UNAVAILABLE", "OFFICIAL_RATE_LIMITED").contains(code) ? code : "OFFICIAL_UNAVAILABLE";
    }
    private State ensureState(UUID id) {
        State state = state(id);
        if (state != null) return state;
        jdbc.update("insert into official_account_quota_polls(official_account_id) values (?)", id);
        return state(id);
    }
    private State state(UUID id) {
        return jdbc.query("select * from official_account_quota_polls where official_account_id=?", (rs, n) -> new State(
                rs.getBoolean("enabled"), rs.getInt("interval_seconds"), rs.getString("status"), instant(rs, "last_attempt_at"),
                instant(rs, "last_success_at"), instant(rs, "next_attempt_at"), rs.getInt("failure_count"), rs.getString("last_error_code"),
                rs.getString("snapshot_json"), rs.getObject("connection_revision", UUID.class), rs.getObject("attempt_id", UUID.class),
                instant(rs, "lease_until"), rs.getBoolean("paused"), rs.getLong("version")), id).stream().findFirst().orElse(null);
    }
    private void requireAccount(UUID id, boolean lock) {
        if ((lock ? accounts.findByIdForUpdate(id) : accounts.findById(id)).isEmpty())
            throw error("OFFICIAL_ACCOUNT_NOT_FOUND", "官方账户不存在。", HttpStatus.NOT_FOUND);
    }
    private static Instant instant(ResultSet rs, String name) throws SQLException {
        Timestamp value = rs.getTimestamp(name); return value == null ? null : value.toInstant();
    }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant later(Instant first, Instant second) {
        return first == null ? second : second == null || first.isAfter(second) ? first : second;
    }
    private static DomainException error(String code, String message, HttpStatus status) { return new DomainException(code, message, status); }
}
