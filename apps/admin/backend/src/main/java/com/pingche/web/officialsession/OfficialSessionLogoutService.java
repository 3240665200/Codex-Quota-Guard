package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Official writes are journaled before dispatch and never automatically retried. */
@Service
public class OfficialSessionLogoutService {
    private final JdbcTemplate jdbc;
    private final OfficialSessionCipher cipher;
    private final OfficialSessionClient client;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(20), task -> {
        Thread thread = new Thread(task, "official-session-logout");
        thread.setDaemon(true);
        return thread;
    });

    public OfficialSessionLogoutService(JdbcTemplate jdbc, OfficialSessionCipher cipher, OfficialSessionClient client,
            ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.client = client;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
    }

    public OfficialSessionLogoutPreview preview(UUID owner, UUID account, String mode) {
        if (!Set.of("ALL", "UNBOUND").contains(mode)) throw error("OFFICIAL_LOGOUT_MODE_INVALID");
        return transaction(() -> {
            lock(account);
            Connection connection = connection(account);
            idle(account, connection);
            OfficialSessionSnapshot snapshot = fresh(connection);
            String bindings = bindingFingerprint(account);
            OfficialSessionLogoutPreview preview = classify(UUID.randomUUID(), account, mode, snapshot, activeSessions(account));
            jdbc.update("insert into official_session_logout_plans(id,owner_id,official_account_id,mode,status,connection_revision,official_account_key,official_user_key,binding_fingerprint,session_fingerprint,preview_json,created_at,expires_at) values(?,?,?,?,'PREVIEW',?,?,?,?,?,?,?,?)",
                    preview.planId(), owner, account, mode, connection.revision, snapshot.account().accountId(), snapshot.account().userId(), bindings,
                    sessionFingerprint(snapshot), json.writeValueAsString(preview), at(clock.instant()), at(preview.expiresAt()));
            return preview;
        });
    }

    public CurrentResponse current(UUID owner, UUID account) {
        return transaction(() -> {
            lock(account);
            List<Plan> plans = jdbc.query("select * from official_session_logout_plans where official_account_id=? and status not in ('PREVIEW','INVALIDATED') order by case when status in ('EXECUTING','UNKNOWN') then 0 else 1 end,started_at desc,id desc limit 1", this::plan, account);
            if (plans.isEmpty()) return new CurrentResponse(null);
            Plan plan = plans.get(0);
            List<OfficialSessionLogoutResult.Item> items = storedItems(plan.id);
            Instant canAcknowledgeAt = "EXECUTING".equals(plan.status) ? plan.leaseUntil.plusSeconds(60) : null;
            boolean eligible = "UNKNOWN".equals(plan.status) || ("EXECUTING".equals(plan.status) && !canAcknowledgeAt.isAfter(clock.instant()));
            return new CurrentResponse(new CurrentOperation(plan.id, plan.mode, plan.status, requiresReauthorization(plan, items), List.copyOf(items),
                    "Verify the official outcome before acknowledging; acknowledging never retries an official request", eligible, canAcknowledgeAt));
        });
    }

    public OfficialSessionLogoutResult confirm(UUID owner, UUID account, UUID id, boolean acknowledge) {
        if (!acknowledge) throw error("OFFICIAL_LOGOUT_ACK_REQUIRED");
        Work work = transaction(() -> {
            lock(account);
            Plan plan = owned(owner, account, id);
            if (!"PREVIEW".equals(plan.status)) throw error("OFFICIAL_LOGOUT_PLAN_CONSUMED");
            if (!plan.expiresAt.isAfter(clock.instant())) throw error("OFFICIAL_LOGOUT_PLAN_EXPIRED");
            Connection connection = connection(account);
            idle(account, connection);
            if (!connection.revision.equals(plan.revision) || !bindingFingerprint(account).equals(plan.bindings)) throw error("OFFICIAL_LOGOUT_CONTEXT_CHANGED");
            jdbc.update("update official_session_logout_plans set status='EXECUTING',started_at=?,lease_until=? where id=?", at(clock.instant()), at(clock.instant().plusSeconds(300)), id);
            jdbc.update("update official_account_connections set snapshot_cipher=null,last_success_at=null,version=version+1 where official_account_id=?", account);
            return new Work(plan, connection);
        });
        try { workers.execute(() -> executeSafely(owner, account, work)); }
        catch (RejectedExecutionException unavailable) { invalidate(id); throw error("OFFICIAL_LOGOUT_BUSY"); }
        return new OfficialSessionLogoutResult(id, work.plan.mode, "EXECUTING", false, List.of(), "The confirmed operation is running; read its status without submitting it again");
    }

    public OfficialSessionLogoutResult result(UUID owner, UUID account, UUID id) {
        return transaction(() -> {
            lock(account);
            Plan plan = owned(owner, account, id);
            if (Set.of("PREVIEW", "INVALIDATED").contains(plan.status)) throw error("OFFICIAL_LOGOUT_PLAN_CONSUMED");
            List<OfficialSessionLogoutResult.Item> items = storedItems(id);
            return new OfficialSessionLogoutResult(id, plan.mode, plan.status, requiresReauthorization(plan, items), items,
                    "EXECUTING".equals(plan.status) ? "The confirmed operation is running" : "Official acceptance may take time to appear; no automatic retry was performed");
        });
    }

    private void executeSafely(UUID owner, UUID account, Work work) {
        try { execute(owner, account, work); }
        catch (DomainException failure) {
            transaction(() -> {
                lock(account);
                boolean dispatched = storedItems(work.plan.id).stream().anyMatch(item -> !"NOT_ATTEMPTED".equals(item.status()));
                String status = dispatched ? "UNKNOWN" : "FAILED";
                jdbc.update("update official_session_logout_plans set status=?,results_json=? where id=? and status='EXECUTING'", status,
                        json.writeValueAsString(new OfficialSessionLogoutResult(work.plan.id, work.plan.mode, status, dispatched && "ALL".equals(work.plan.mode),
                                dispatched ? storedItems(work.plan.id) : List.of(new OfficialSessionLogoutResult.Item(null, "NOT_ATTEMPTED", safeCode(failure.getCode()))), "Operation could not be safely completed")), work.plan.id);
                return null;
            });
        } catch (RuntimeException ignored) {
            // Keep the durable EXECUTING fence: a process/DB failure cannot prove the upstream outcome.
        }
    }

    private OfficialSessionLogoutResult execute(UUID owner, UUID account, Work work) {
        UUID id = work.plan.id;
        OfficialSessionSnapshot snapshot;
        try { snapshot = fresh(work.connection); }
        catch (RuntimeException failure) {
            if (failure instanceof DomainException domain) throw domain;
            throw error("OFFICIAL_LOGOUT_SNAPSHOT_INCOMPLETE");
        }
        boolean same = transaction(() -> {
            lock(account);
            Plan plan = owned(owner, account, id);
            Connection current = connection(account);
            boolean valid = "EXECUTING".equals(plan.status) && current.revision.equals(plan.revision)
                    && plan.bindings.equals(bindingFingerprint(account)) && plan.sessions.equals(sessionFingerprint(snapshot))
                    && plan.accountKey.equals(snapshot.account().accountId()) && plan.userKey.equals(snapshot.account().userId());
            return valid;
        });
        if (!same) throw error("OFFICIAL_LOGOUT_CONTEXT_CHANGED");
        OfficialSessionLogoutPreview preview = json.readValue(work.plan.preview, OfficialSessionLogoutPreview.class);
        List<OfficialSessionLogoutResult.Item> items = new ArrayList<>();
        if ("ALL".equals(work.plan.mode)) items.add(new OfficialSessionLogoutResult.Item(null, "NOT_ATTEMPTED", null));
        else for (var target : preview.targets()) items.add(new OfficialSessionLogoutResult.Item(target.sessionId(), "NOT_ATTEMPTED", null));
        boolean uncertain = false;
        for (int index = 0; index < items.size(); index++) {
            if ("UNBOUND".equals(work.plan.mode)) {
                String stop = revalidateTarget(work, items.get(index).sessionId());
                if (stop != null) {
                    items.set(index, new OfficialSessionLogoutResult.Item(items.get(index).sessionId(), "NOT_ATTEMPTED", stop));
                    break;
                }
            }
            final int next = index;
            boolean dispatch = transaction(() -> {
                lock(account);
                Plan plan = owned(owner, account, id);
                if (!"EXECUTING".equals(plan.status) || !connection(account).revision.equals(plan.revision)
                        || !plan.bindings.equals(bindingFingerprint(account)) || !plan.leaseUntil.isAfter(clock.instant())) return false;
                // UNKNOWN is durable before dispatch, including process death after the upstream accepts.
                items.set(next, new OfficialSessionLogoutResult.Item(items.get(next).sessionId(), "UNKNOWN", "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN"));
                journal(id, items);
                return true;
            });
            if (!dispatch) {
                items.set(index, new OfficialSessionLogoutResult.Item(items.get(index).sessionId(), "NOT_ATTEMPTED", "OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
                break;
            }
            // The journal above is committed before this transaction. Hold the common account
            // lock from the final fence check through the bounded POST so acknowledgement
            // cannot release an unsent, paused worker and permit a late write.
            OfficialSessionWriteResult result = transaction(() -> {
                lock(account);
                Plan plan = owned(owner, account, id);
                if (!"EXECUTING".equals(plan.status) || !plan.leaseUntil.isAfter(clock.instant())
                        || !connection(account).revision.equals(plan.revision) || !plan.bindings.equals(bindingFingerprint(account))) return null;
                OfficialSessionWriteResult outcome;
                try {
                    outcome = "ALL".equals(work.plan.mode) ? client.logoutAll(credentials(work.connection), work.connection.proxy)
                            : client.revokeSession(credentials(work.connection), work.connection.proxy, items.get(next).sessionId());
                } catch (RuntimeException failure) { outcome = new OfficialSessionWriteResult("UNKNOWN", "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN"); }
                if (outcome == null) outcome = new OfficialSessionWriteResult("UNKNOWN", "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN");
                String itemStatus = "ACCEPTED".equals(outcome.status()) ? "ACCEPTED" : "REJECTED".equals(outcome.status()) ? "FAILED" : "UNKNOWN";
                items.set(next, new OfficialSessionLogoutResult.Item(items.get(next).sessionId(), itemStatus,
                        "ACCEPTED".equals(itemStatus) ? null : safeCode(outcome.errorCode())));
                journal(id, items);
                return outcome;
            });
            if (result == null) {
                items.set(index, new OfficialSessionLogoutResult.Item(items.get(index).sessionId(), "NOT_ATTEMPTED", "OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
                break;
            }
            if ("UNKNOWN".equals(result.status())) uncertain = true;
            if (!"ACCEPTED".equals(items.get(index).status())) break;
        }
        long accepted = items.stream().filter(item -> "ACCEPTED".equals(item.status())).count();
        String status = uncertain ? "UNKNOWN" : accepted == items.size() ? "ACCEPTED" : accepted > 0 ? "PARTIAL" : "FAILED";
        boolean attemptedAll = "ALL".equals(work.plan.mode) && items.stream().anyMatch(item -> !"NOT_ATTEMPTED".equals(item.status()) && !"FAILED".equals(item.status()));
        OfficialSessionLogoutResult result = new OfficialSessionLogoutResult(id, work.plan.mode, status, attemptedAll, List.copyOf(items),
                "UNKNOWN".equals(status) ? "Official outcome is uncertain; verify on the official website before acknowledging the local lock" : "Official acceptance may take time to appear; no automatic retry was performed");
        transaction(() -> {
            lock(account);
            Plan current = owned(owner, account, id);
            if (!"EXECUTING".equals(current.status) || !connection(account).revision.equals(work.plan.revision)) return null;
            if (items.stream().anyMatch(item -> !"NOT_ATTEMPTED".equals(item.status()))) clearSnapshot(account, attemptedAll);
            jdbc.update("update official_session_logout_plans set status=?,results_json=? where id=? and status='EXECUTING'", status, json.writeValueAsString(result), id);
            return null;
        });
        return result;
    }

    public OfficialSessionLogoutResult acknowledgeUnknown(UUID owner, UUID account, UUID id, boolean acknowledge) {
        if (!acknowledge) throw error("OFFICIAL_LOGOUT_ACK_REQUIRED");
        return transaction(() -> {
            lock(account);
            Plan plan = accountPlan(account, id);
            if ("EXECUTING".equals(plan.status) && (plan.leaseUntil == null || plan.leaseUntil.plusSeconds(60).isAfter(clock.instant())))
                throw error("OFFICIAL_LOGOUT_STILL_RUNNING");
            if (!Set.of("EXECUTING", "UNKNOWN").contains(plan.status)) throw error("OFFICIAL_LOGOUT_PLAN_CONSUMED");
            if (!connection(account).revision.equals(plan.revision)) throw error("OFFICIAL_LOGOUT_CONTEXT_CHANGED");
            // RESOLVED_UNKNOWN is the execution fence checked by every dispatch and completion.
            // Keep UNBOUND's connection revision so unrelated ACTIVE bindings remain valid.
            clearSnapshot(account, "ALL".equals(plan.mode));
            if ("ALL".equals(plan.mode)) jdbc.update("update official_account_connections set revision=?,version=version+1 where official_account_id=?", UUID.randomUUID(), account);
            OfficialSessionLogoutResult result = new OfficialSessionLogoutResult(id, plan.mode, "RESOLVED_UNKNOWN", "ALL".equals(plan.mode), storedItems(id),
                    "Local lock acknowledged after official verification; this does not undo or retry any official request");
            jdbc.update("update official_session_logout_plans set status='RESOLVED_UNKNOWN',acknowledged_at=?,acknowledged_by=?,results_json=? where id=?", at(clock.instant()), owner, json.writeValueAsString(result), id);
            return result;
        });
    }

    private OfficialSessionLogoutPreview classify(UUID id, UUID account, String mode, OfficialSessionSnapshot snapshot, Set<String> bound) {
        List<OfficialSessionLogoutPreview.Target> targets = new ArrayList<>();
        List<OfficialSessionLogoutPreview.Exclusion> excluded = new ArrayList<>();
        Map<String, Integer> counts = new HashMap<>(), rows = new HashMap<>();
        for (var device : snapshot.sessions().devices()) {
            if (device.sessionId() != null) counts.merge(device.sessionId(), 1, Integer::sum);
            if (device.renderId() != null) rows.merge(device.renderId(), 1, Integer::sum);
        }
        for (var device : snapshot.sessions().devices()) {
            var target = new OfficialSessionLogoutPreview.Target(device.renderId(), device.sessionId(), device.displayName(), device.platform(), device.trustedDevice(), device.currentDevice());
            String reason = null;
            if ("UNBOUND".equals(mode)) {
                if (device.sessionId() == null || device.sessionId().isBlank() || device.sessionId().length() > 512) reason = "MISSING_SESSION_ID";
                else if (counts.getOrDefault(device.sessionId(), 0) > 1) reason = "DUPLICATE_SESSION_ID";
                else if (device.renderId() != null && rows.getOrDefault(device.renderId(), 0) > 1) reason = "DUPLICATE_ROW_ID";
                else if (bound.contains(device.sessionId())) reason = "ACTIVE_BINDING";
                else if (Boolean.TRUE.equals(device.trustedDevice())) reason = "TRUSTED_DEVICE";
                else if (device.trustedDevice() == null) reason = "TRUSTED_DEVICE_UNKNOWN";
                else if (Boolean.TRUE.equals(device.currentDevice())) reason = "CURRENT_SESSION";
                else if (device.currentDevice() == null) reason = "CURRENT_SESSION_UNKNOWN";
            }
            if (reason == null) targets.add(target); else excluded.add(new OfficialSessionLogoutPreview.Exclusion(target, reason));
        }
        return new OfficialSessionLogoutPreview(id, account, mode, clock.instant().plusSeconds(120), List.copyOf(targets), List.copyOf(excluded),
                "ALL".equals(mode) ? List.of("ALL_SESSIONS_GLOBAL_ACTION", "ALL_REAUTHORIZATION_REQUIRED", "OFFICIAL_PROPAGATION_DELAY") : List.of("OFFICIAL_PROPAGATION_DELAY"));
    }

    private void idle(UUID account, Connection connection) {
        if (jdbc.queryForObject("select count(*) from official_session_logout_plans where official_account_id=? and status in ('EXECUTING','UNKNOWN')", Integer.class, account) > 0
                || connection.refreshAttempt != null
                || jdbc.queryForObject("select count(*) from session_binding_attempts where official_account_id=? and status in ('QUEUED','PREPARING','READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')", Integer.class, account) > 0
                || jdbc.queryForObject("select count(*) from session_binding_accounts where official_account_id=? and (active_attempt_id is not null or recovery_id is not null or quarantined=true)", Integer.class, account) > 0
                || jdbc.queryForObject("select count(*) from official_browser_login_jobs where official_account_id=? and state in ('WAITING_FOR_HELPER','WAITING_FOR_LOGIN','SAVING')", Integer.class, account) > 0)
            throw error("OFFICIAL_LOGOUT_BUSY");
        if (jdbc.queryForObject("select count(*) from official_account_connections where requested_account_id=?", Integer.class, connection.requested) != 1)
            throw error("OFFICIAL_LOGOUT_DUPLICATE_IDENTITY");
        if (!Set.of("UNVERIFIED", "CONNECTED").contains(connection.status)) throw error("OFFICIAL_LOGOUT_CONNECTION_UNAVAILABLE");
    }

    private OfficialSessionSnapshot fresh(Connection connection) {
        OfficialSessionSnapshot result = client.fetch(credentials(connection), connection.proxy);
        if (result == null || result.account() == null || result.sessions() == null || !result.sessions().showSessionManager()
                || result.sessions().devices() == null || result.sessions().devices().stream().anyMatch(Objects::isNull)
                || !connection.requested.equals(result.account().accountId()) || result.account().userId() == null || result.account().userId().isBlank()
                || Boolean.TRUE.equals(result.account().deactivated())
                || (connection.boundUser != null && !connection.boundUser.equals(result.account().userId()))
                || (connection.boundAccount != null && !connection.boundAccount.equals(result.account().accountId()))) throw error("OFFICIAL_LOGOUT_SNAPSHOT_INCOMPLETE");
        return result;
    }

    private String revalidateTarget(Work work, String sessionId) {
        try {
            OfficialSessionSnapshot latest = fresh(work.connection);
            if (!work.plan.accountKey.equals(latest.account().accountId()) || !work.plan.userKey.equals(latest.account().userId())) return "OFFICIAL_LOGOUT_CONTEXT_CHANGED";
            List<OfficialSessionInfo> matches = latest.sessions().devices().stream().filter(device -> sessionId.equals(device.sessionId())).toList();
            if (matches.size() != 1) return "OFFICIAL_LOGOUT_CONTEXT_CHANGED";
            OfficialSessionInfo target = matches.get(0);
            if (!Boolean.FALSE.equals(target.trustedDevice()) || !Boolean.FALSE.equals(target.currentDevice())
                    || (target.renderId() != null && latest.sessions().devices().stream().filter(device -> target.renderId().equals(device.renderId())).count() != 1)) return "OFFICIAL_LOGOUT_CONTEXT_CHANGED";
            return null;
        } catch (RuntimeException failure) { return "OFFICIAL_LOGOUT_SNAPSHOT_INCOMPLETE"; }
    }

    private OfficialSessionCredentials credentials(Connection connection) {
        String aad = "official-session:v1:" + connection.id + ":";
        return new OfficialSessionCredentials(cipher.decrypt(connection.authorization, aad + "authorization"), connection.cookie == null ? null : cipher.decrypt(connection.cookie, aad + "cookie"), connection.requested);
    }

    private Connection connection(UUID account) {
        var rows = jdbc.query("select * from official_account_connections where official_account_id=? for update", (rs,n) -> new Connection(account, rs.getObject("revision", UUID.class), rs.getString("requested_account_id"), rs.getObject("proxy_node_id", UUID.class), rs.getString("authorization_cipher"), rs.getString("cookie_cipher"), rs.getString("bound_account_id"), rs.getString("bound_user_id"), rs.getObject("refresh_attempt", UUID.class), rs.getString("status")), account);
        if (rows.isEmpty()) throw error("OFFICIAL_LOGOUT_CONNECTION_UNAVAILABLE");
        return rows.get(0);
    }

    private Plan owned(UUID owner, UUID account, UUID id) {
        Plan plan = accountPlan(account, id);
        if (!plan.owner.equals(owner)) throw new DomainException("OFFICIAL_LOGOUT_PLAN_FORBIDDEN", "Logout plan is unavailable", HttpStatus.FORBIDDEN);
        return plan;
    }

    private Plan accountPlan(UUID account, UUID id) {
        var rows = jdbc.query("select * from official_session_logout_plans where id=?", this::plan, id);
        if (rows.isEmpty() || !rows.get(0).account.equals(account))
            throw new DomainException("OFFICIAL_LOGOUT_PLAN_FORBIDDEN", "Logout plan is unavailable", HttpStatus.FORBIDDEN);
        return rows.get(0);
    }

    private Plan plan(ResultSet rs, int n) throws SQLException {
        return new Plan(rs.getObject("id", UUID.class), rs.getObject("owner_id", UUID.class), rs.getObject("official_account_id", UUID.class), rs.getString("mode"), rs.getString("status"), rs.getObject("connection_revision", UUID.class), rs.getString("official_account_key"), rs.getString("official_user_key"), rs.getString("binding_fingerprint"), rs.getString("session_fingerprint"), rs.getString("preview_json"), rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("lease_until") == null ? null : rs.getTimestamp("lease_until").toInstant());
    }

    private Set<String> activeSessions(UUID account) { return Set.copyOf(jdbc.query("select session_id from session_bindings where official_account_id=? and status='ACTIVE'", (rs,n) -> rs.getString(1), account)); }
    private String bindingFingerprint(UUID account) {
        return hash(json.writeValueAsString(jdbc.query("select id,session_id,connection_revision from session_bindings where official_account_id=? and status='ACTIVE' order by id", (rs,n) -> List.of(rs.getObject("id").toString(), rs.getString("session_id"), rs.getObject("connection_revision").toString()), account)));
    }
    private String sessionFingerprint(OfficialSessionSnapshot snapshot) {
        List<String> security = snapshot.sessions().devices().stream().map(device -> json.writeValueAsString(new SecurityIdentity(device.renderId(), device.sessionId(), device.currentDevice(), device.trustedDevice()))).sorted().toList();
        return hash(json.writeValueAsString(security));
    }
    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void lock(UUID account) {
        if (jdbc.query("select id from official_accounts where id=? for update", (rs,n) -> rs.getObject(1, UUID.class), account).isEmpty()) throw error("OFFICIAL_ACCOUNT_NOT_FOUND");
    }
    private void journal(UUID id, List<OfficialSessionLogoutResult.Item> items) {
        jdbc.update("update official_session_logout_plans set results_json=? where id=? and status='EXECUTING'", json.writeValueAsString(items), id);
    }
    private List<OfficialSessionLogoutResult.Item> storedItems(UUID id) {
        String stored = jdbc.queryForObject("select results_json from official_session_logout_plans where id=?", String.class, id);
        List<OfficialSessionLogoutResult.Item> items = new ArrayList<>();
        if (stored != null) {
            var node = json.readTree(stored);
            for (var row : node.isArray() ? node : node.path("results")) items.add(json.treeToValue(row, OfficialSessionLogoutResult.Item.class));
        }
        return List.copyOf(items);
    }
    private boolean requiresReauthorization(Plan plan, List<OfficialSessionLogoutResult.Item> items) {
        return "ALL".equals(plan.mode) && ("RESOLVED_UNKNOWN".equals(plan.status) || items.stream().anyMatch(item -> Set.of("ACCEPTED", "UNKNOWN").contains(item.status())));
    }
    private void invalidate(UUID id) { transaction(() -> { jdbc.update("update official_session_logout_plans set status='INVALIDATED' where id=? and status='EXECUTING'", id); return null; }); }
    private void clearSnapshot(UUID account, boolean all) {
        jdbc.update("update official_account_connections set snapshot_cipher=null,last_success_at=null,status=?,last_error_code=?,refresh_attempt=null,refresh_lease_until=null,version=version+1 where official_account_id=?", all ? "AUTH_REQUIRED" : "UNVERIFIED", all ? "OFFICIAL_AUTH_REQUIRED" : null, account);
        if (all) {
            jdbc.update("update session_binding_attempts set status='REJECTED',error_code='OFFICIAL_LOGOUT_ALL' where id in (select attempt_id from session_bindings where official_account_id=? and status='ACTIVE')", account);
            jdbc.update("update session_bindings set status='INVALIDATED',active_device_id=null,active_session_id=null,ended_at=? where official_account_id=? and status='ACTIVE'", at(clock.instant()), account);
        }
    }
    private String safeCode(String value) { return value != null && value.matches("OFFICIAL_[A-Z0-9_]{1,55}") ? value : "OFFICIAL_LOGOUT_REJECTED"; }
    private static OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private DomainException error(String code) { return new DomainException(code, "Official session logout could not be safely completed", HttpStatus.CONFLICT); }
    private <T> T transaction(Supplier<T> work) { return tx.execute(ignored -> work.get()); }
    private record Connection(UUID id, UUID revision, String requested, UUID proxy, String authorization, String cookie, String boundAccount, String boundUser, UUID refreshAttempt, String status) {
        @Override public String toString() { return "OfficialLogoutConnection[redacted]"; }
    }
    private record Plan(UUID id, UUID owner, UUID account, String mode, String status, UUID revision, String accountKey, String userKey, String bindings, String sessions, String preview, Instant expiresAt, Instant leaseUntil) { }
    private record Work(Plan plan, Connection connection) { }
    private record SecurityIdentity(String rowId, String sessionId, Boolean current, Boolean trusted) { }
    @PreDestroy void close() { workers.shutdown(); }
    public record CurrentResponse(CurrentOperation operation) { }
    public record CurrentOperation(UUID planId, String mode, String status, boolean requiresReauthorization,
            List<OfficialSessionLogoutResult.Item> results, String message, boolean canAcknowledge, Instant canAcknowledgeAt) { }
}
