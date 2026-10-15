package com.pingche.web.sessionbinding;

import com.pingche.web.auth.DomainException;
import com.pingche.web.officialsession.OfficialConnectionRefreshResponse;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.officialsession.OfficialSessionInfo;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** A confirmed serial-login inference, never a cryptographic device attestation. */
@Service
public class SessionBindingService {
    private static final String PENDING = "('QUEUED','PREPARING','READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')";
    private final JdbcTemplate jdbc;
    private final OfficialConnectionService official;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public SessionBindingService(JdbcTemplate jdbc, OfficialConnectionService official, Clock clock,
            ObjectMapper json, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.official = official;
        this.clock = clock;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public SessionBindingAttemptResponse create(UUID userId, UUID deviceId, UUID enrollmentId, UUID requestedAccount) {
        if (deviceId == null || enrollmentId == null) throw error("SESSION_BINDING_DEVICE_REQUIRED");
        if (!deviceId.equals(enrollmentId)) throw forbidden();
        Context initial = context(userId, enrollmentId, null, false);
        UUID accountId = initial.accountId;
        if (requestedAccount != null && !requestedAccount.equals(accountId)) throw forbidden();
        return transaction(() -> {
            lockAccount(accountId);
            sweep(accountId);
            invalidateBindings(accountId);
            Context current = context(userId, enrollmentId, accountId, true);
            if (jdbc.queryForObject("select count(*) from session_bindings where active_device_id=?", Integer.class, deviceId) > 0)
                throw error("SESSION_BINDING_DEVICE_CONFLICT");
            List<Attempt> existing = jdbc.query("select * from session_binding_attempts where user_id=? and device_id=? and status in " + PENDING + " order by sequence_id desc", this::attempt, userId, deviceId);
            if (!existing.isEmpty()) return response(existing.get(0));
            UUID id = UUID.randomUUID();
            jdbc.update("insert into session_binding_attempts (id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                    id, userId, deviceId, enrollmentId, current.teamId, current.teamVersion, accountId, current.revision, "QUEUED", clock.instant(), clock.instant().plusSeconds(1800));
            return response(load(id));
        });
    }

    public SessionBindingAttemptResponse get(UUID userId, UUID id) {
        return withAttempt(userId, id, () -> response(load(id)));
    }

    public SessionBindingCurrentResponse current(UUID userId, UUID deviceId, UUID enrollmentId) {
        if (deviceId == null || !deviceId.equals(enrollmentId)) throw forbidden();
        context(userId, enrollmentId, null, false);
        List<Attempt> attempts = jdbc.query("select * from session_binding_attempts where user_id=? and device_id=? order by sequence_id desc limit 1", this::attempt, userId, deviceId);
        // The user's current team may differ from the account of an older task.
        // Always validate that task under its own account lock before exposing it.
        SessionBindingAttemptResponse active = attempts.isEmpty() ? null : get(userId, attempts.get(0).id);
        List<SessionBindingResponse> previous = bindingRows("where b.user_id=? and b.device_id=? and b.status='ACTIVE'", userId, deviceId);
        for (SessionBindingResponse binding : previous) transaction(() -> { lockAccount(binding.officialAccountId()); invalidateBindings(binding.officialAccountId()); return null; });
        List<SessionBindingResponse> bindings = bindingRows("where b.user_id=? and b.device_id=? and b.status='ACTIVE'", userId, deviceId);
        return new SessionBindingCurrentResponse(active, bindings.isEmpty() ? null : bindings.get(0));
    }

    public SessionBindingAttemptResponse prepare(UUID userId, UUID id) {
        Work work = withAttempt(userId, id, () -> {
            Attempt attempt = load(id);
            if (!"QUEUED".equals(attempt.status) || accountStateUnlocked(attempt.accountId).quarantined()) return null;
            UUID head = jdbc.query("select id from session_binding_attempts where official_account_id=? and status in " + PENDING + " order by sequence_id limit 1", (rs, n) -> uuid(rs, "id"), attempt.accountId).stream().findFirst().orElse(null);
            if (!id.equals(head)) return null;
            if (!valid(attempt)) { terminate(attempt, "REJECTED", "SESSION_BINDING_CONTEXT_CHANGED"); return null; }
            UUID operation = UUID.randomUUID();
            jdbc.update("update session_binding_accounts set active_attempt_id=?,updated_at=? where official_account_id=?", id, clock.instant(), attempt.accountId);
            jdbc.update("update session_binding_attempts set status='PREPARING',operation_id=?,operation_started_at=?,expires_at=? where id=?", operation, clock.instant(), clock.instant().plusSeconds(600), id);
            return new Work(load(id), operation, "PREPARING");
        });
        if (work == null) return get(userId, id);
        OfficialConnectionRefreshResponse snapshot;
        try {
            snapshot = fresh(work);
        } catch (DomainException failure) { return fail(work, failure.getCode()); }
        return finish(work, () -> {
            Attempt attempt = load(id);
            jdbc.update("update session_binding_attempts set status='READY_FOR_LOGIN',before_session_ids=?,official_account_key=?,official_user_key=?,operation_id=null,operation_started_at=null where id=?",
                    json.writeValueAsString(snapshot.sessions().devices().stream().map(OfficialSessionInfo::sessionId).filter(idValue -> idValue != null && !idValue.isBlank()).toList()),
                    snapshot.account().accountId(), snapshot.account().userId(), id);
            return response(load(id));
        });
    }

    public SessionBindingAttemptResponse detect(UUID userId, UUID id) {
        Work work = withAttempt(userId, id, () -> {
            Attempt attempt = load(id);
            if (!"READY_FOR_LOGIN".equals(attempt.status) || accountStateUnlocked(attempt.accountId).quarantined()) return null;
            return reserveAfter(attempt);
        });
        if (work == null) return get(userId, id);
        OfficialConnectionRefreshResponse snapshot;
        try { snapshot = fresh(work); }
        catch (DomainException failure) { return fail(work, failure.getCode()); }
        return finish(work, () -> identify(load(id), snapshot));
    }

    public SessionBindingAttemptResponse confirm(UUID userId, UUID id, String sessionId) {
        Work refresh = withAttempt(userId, id, () -> {
            Attempt attempt = load(id);
            if (!"AWAITING_CONFIRMATION".equals(attempt.status)) return null;
            if (!Objects.equals(sessionId, attempt.candidateSessionId)) throw error("SESSION_BINDING_CANDIDATE_MISMATCH");
            if (attempt.candidateFetchedAt != null && !attempt.candidateFetchedAt.plusSeconds(60).isBefore(clock.instant())) return null;
            return reserveAfter(attempt);
        });
        if (refresh != null) {
            OfficialConnectionRefreshResponse snapshot;
            try { snapshot = fresh(refresh); }
            catch (DomainException failure) { return fail(refresh, failure.getCode()); }
            SessionBindingAttemptResponse checked = finish(refresh, () -> identify(load(id), snapshot));
            if (!"AWAITING_CONFIRMATION".equals(checked.status())) return checked;
        }
        try {
            return withAttempt(userId, id, () -> {
                Attempt attempt = load(id);
                if ("BOUND".equals(attempt.status)) return response(attempt);
                if (!"AWAITING_CONFIRMATION".equals(attempt.status)) return response(attempt);
                if (!Objects.equals(sessionId, attempt.candidateSessionId)) throw error("SESSION_BINDING_CANDIDATE_MISMATCH");
                if (accountStateUnlocked(attempt.accountId).quarantined() || !valid(attempt)) {
                    terminate(attempt, "REJECTED", "SESSION_BINDING_CONTEXT_CHANGED");
                    return response(load(id));
                }
                if (jdbc.queryForObject("select count(*) from session_bindings where active_device_id=? or (official_account_id=? and active_session_id=?)", Integer.class, attempt.deviceId, attempt.accountId, sessionId) > 0) {
                    terminate(attempt, "REJECTED", "SESSION_BINDING_CURRENT_CONFLICT");
                    return response(load(id));
                }
                jdbc.update("insert into session_bindings (id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), id, userId, attempt.deviceId, attempt.enrollmentId, attempt.teamId, attempt.teamVersion,
                        attempt.accountId, attempt.revision, sessionId, attempt.deviceId, sessionId, "ACTIVE", "SERIAL_LOGIN_DIFF", "USER_CONFIRMED_INFERENCE", clock.instant());
                jdbc.update("update session_binding_attempts set status='BOUND' where id=?", id);
                jdbc.update("update session_binding_accounts set active_attempt_id=null,updated_at=? where official_account_id=? and active_attempt_id=?", clock.instant(), attempt.accountId, id);
                return response(load(id));
            });
        } catch (DataIntegrityViolationException conflict) {
            // Database uniqueness remains authoritative across different account queues.
            return withAttempt(userId, id, () -> {
                terminate(load(id), "REJECTED", "SESSION_BINDING_CURRENT_CONFLICT");
                return response(load(id));
            });
        }
    }

    public SessionBindingAttemptResponse cancel(UUID userId, UUID id) {
        return withAttempt(userId, id, () -> {
            Attempt attempt = load(id);
            if (pending(attempt.status)) terminate(attempt, "CANCELLED", "SESSION_BINDING_CANCELLED");
            return response(load(id));
        });
    }

    public void release(UUID userId, UUID bindingId) {
        List<SessionBindingResponse> found = bindingRows("where b.id=? and b.user_id=?", bindingId, userId);
        if (found.isEmpty()) throw forbidden();
        transaction(() -> {
            lockAccount(found.get(0).officialAccountId());
            jdbc.update("update session_bindings set status='ENDED',active_device_id=null,active_session_id=null,ended_at=? where id=? and user_id=? and status='ACTIVE'", clock.instant(), bindingId, userId);
            return null;
        });
    }

    public List<SessionBindingResponse> bindings(UUID accountId) {
        List<UUID> ids = accountId == null ? jdbc.query("select id from official_accounts order by id", (rs,n) -> uuid(rs,"id")) : List.of(accountId);
        for (UUID id : ids) transaction(() -> { lockAccount(id); sweep(id); invalidateBindings(id); return null; });
        return accountId == null ? bindingRows("") : bindingRows("where b.official_account_id=?", accountId);
    }

    public SessionBindingAccountResponse accountState(UUID accountId) {
        return transaction(() -> { lockAccount(accountId); sweep(accountId); return accountStateUnlocked(accountId); });
    }

    /** Explicit operator acknowledgement is required because a late external login cannot be ruled out. */
    public SessionBindingAccountResponse recover(UUID accountId, boolean acknowledgeLateLoginRisk) {
        if (!acknowledgeLateLoginRisk) throw error("SESSION_BINDING_RECOVERY_ACK_REQUIRED");
        UUID recovery = UUID.randomUUID();
        UUID revision = transaction(() -> {
            lockAccount(accountId);
            sweep(accountId);
            if (accountStateUnlocked(accountId).activeAttemptId() != null) throw error("SESSION_BINDING_ACCOUNT_BUSY");
            jdbc.update("update session_binding_accounts set quarantined=true,reason='SESSION_BINDING_RECOVERY_IN_PROGRESS',recovery_id=?,recovery_started_at=?,updated_at=? where official_account_id=?", recovery, clock.instant(), clock.instant(), accountId);
            return connectionRevision(accountId);
        });
        try {
            Instant refreshStartedAt = clock.instant();
            OfficialConnectionRefreshResponse snapshot = official.refresh(accountId);
            validateSnapshot(snapshot, refreshStartedAt);
            return transaction(() -> {
                lockAccount(accountId);
                UUID actualRecovery = jdbc.queryForObject("select recovery_id from session_binding_accounts where official_account_id=?", UUID.class, accountId);
                if (!recovery.equals(actualRecovery) || !revision.equals(connectionRevision(accountId))) throw error("SESSION_BINDING_CONTEXT_CHANGED");
                jdbc.update("update session_binding_accounts set quarantined=false,reason=null,recovery_id=null,recovery_started_at=null,updated_at=? where official_account_id=?", clock.instant(), accountId);
                return accountStateUnlocked(accountId);
            });
        } catch (RuntimeException failure) {
            transaction(() -> {
                lockAccount(accountId);
                jdbc.update("update session_binding_accounts set reason='SESSION_BINDING_RECOVERY_FAILED',recovery_id=null,recovery_started_at=null,updated_at=? where official_account_id=? and recovery_id=?", clock.instant(), accountId, recovery);
                return null;
            });
            if (failure instanceof DomainException domain) throw domain;
            throw error("SESSION_BINDING_RECOVERY_FAILED");
        }
    }

    private Work reserveAfter(Attempt attempt) {
        UUID operation = UUID.randomUUID();
        jdbc.update("update session_binding_attempts set status='DETECTING',operation_id=?,operation_started_at=? where id=?", operation, clock.instant(), attempt.id);
        return new Work(load(attempt.id), operation, "DETECTING");
    }

    private OfficialConnectionRefreshResponse fresh(Work work) {
        try {
            OfficialConnectionRefreshResponse result = official.refresh(work.attempt.accountId);
            validateSnapshot(result, work.attempt.operationStartedAt);
            String requested = jdbc.queryForObject("select requested_account_id from official_account_connections where official_account_id=?", String.class, work.attempt.accountId);
            if (!requested.equals(result.account().accountId())) throw error("SESSION_BINDING_IDENTITY_CHANGED");
            if (work.attempt.officialUserKey != null && (!work.attempt.officialUserKey.equals(result.account().userId())
                    || !work.attempt.officialAccountKey.equals(result.account().accountId()))) throw error("SESSION_BINDING_IDENTITY_CHANGED");
            return result;
        } catch (DomainException failure) { throw failure; }
        catch (RuntimeException failure) { throw error("SESSION_BINDING_SNAPSHOT_UNAVAILABLE"); }
    }

    private void validateSnapshot(OfficialConnectionRefreshResponse result, Instant earliest) {
        if (result == null || result.account() == null || result.sessions() == null || !result.sessions().showSessionManager()
                || result.fetchedAt() == null || result.fetchedAt().isBefore(earliest.minusSeconds(1)) || result.fetchedAt().isAfter(clock.instant().plusSeconds(5))
                || result.account().userId() == null || result.account().userId().isBlank()
                || result.account().accountId() == null || result.account().accountId().isBlank()
                || Boolean.TRUE.equals(result.account().deactivated())) throw error("SESSION_BINDING_SNAPSHOT_INCOMPLETE");
        Set<String> ids = new HashSet<>();
        for (OfficialSessionInfo session : result.sessions().devices()) {
            if (session != null && (session.sessionId() == null || session.sessionId().isBlank())
                    && Boolean.TRUE.equals(session.trustedDevice()) && session.applications().isEmpty()) continue;
            if (session == null || session.sessionId() == null || session.sessionId().isBlank() || session.sessionId().length() > 512 || !ids.add(session.sessionId()))
                throw error("SESSION_BINDING_SNAPSHOT_INCOMPLETE");
        }
    }

    private SessionBindingAttemptResponse identify(Attempt attempt, OfficialConnectionRefreshResponse snapshot) {
        Set<String> before = new HashSet<>();
        for (var id : json.readTree(attempt.beforeSessionIds)) before.add(id.asString());
        List<OfficialSessionInfo> candidates = snapshot.sessions().devices().stream()
                .filter(device -> device.sessionId() != null && !device.sessionId().isBlank())
                .filter(device -> !before.contains(device.sessionId()))
                .filter(device -> device.applications().stream().anyMatch(app -> app != null && "Codex".equalsIgnoreCase(app.trim()))).toList();
        if (candidates.size() != 1 || (attempt.candidateSessionId != null && !attempt.candidateSessionId.equals(candidates.get(0).sessionId()))) {
            terminate(attempt, "REJECTED", candidates.isEmpty() ? "SESSION_BINDING_NO_NEW_SESSION" : "SESSION_BINDING_AMBIGUOUS");
        } else {
            OfficialSessionInfo candidate = candidates.get(0);
            jdbc.update("update session_binding_attempts set status='AWAITING_CONFIRMATION',candidate_session_id=?,candidate_display_name=?,candidate_platform=?,candidate_fetched_at=?,operation_id=null,operation_started_at=null where id=?",
                    candidate.sessionId(), truncate(candidate.displayName(), 1000), truncate(candidate.platform(), 320), snapshot.fetchedAt(), attempt.id);
        }
        return response(load(attempt.id));
    }

    private SessionBindingAttemptResponse finish(Work work, Supplier<SessionBindingAttemptResponse> completion) {
        return transaction(() -> {
            lockAccount(work.attempt.accountId);
            sweep(work.attempt.accountId);
            Attempt current = load(work.attempt.id);
            if (!work.operation.equals(current.operationId) || !work.status.equals(current.status)) return response(current);
            if (!valid(current)) { terminate(current, "REJECTED", "SESSION_BINDING_CONTEXT_CHANGED"); return response(load(current.id)); }
            return completion.get();
        });
    }

    private SessionBindingAttemptResponse fail(Work work, String code) {
        return finish(work, () -> {
            terminate(load(work.attempt.id), "REJECTED", code);
            return response(load(work.attempt.id));
        });
    }

    private <T> T withAttempt(UUID userId, UUID id, Supplier<T> work) {
        Attempt initial = load(id);
        if (!initial.userId.equals(userId)) throw forbidden();
        return transaction(() -> {
            lockAccount(initial.accountId);
            sweep(initial.accountId);
            invalidateBindings(initial.accountId);
            if (!load(id).userId.equals(userId)) throw forbidden();
            return work.get();
        });
    }

    private void lockAccount(UUID accountId) {
        if (jdbc.query("select id from official_accounts where id=? for update", (rs,n) -> uuid(rs,"id"), accountId).isEmpty()) throw forbidden();
        if (jdbc.queryForObject("select count(*) from session_binding_accounts where official_account_id=?", Integer.class, accountId) == 0)
            jdbc.update("insert into session_binding_accounts (official_account_id,quarantined,updated_at) values (?,false,?)", accountId, clock.instant());
    }

    private void sweep(UUID accountId) {
        UUID active = accountStateUnlocked(accountId).activeAttemptId();
        if (active != null && jdbc.queryForObject("select count(*) from session_binding_attempts where id=? and official_account_id=? and status in ('PREPARING','READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')", Integer.class, active, accountId) == 0) {
            // Cascading deletion of a user must not erase the late-login fence.
            jdbc.update("update session_binding_accounts set quarantined=true,reason='SESSION_BINDING_ORPHANED_ATTEMPT',active_attempt_id=null,updated_at=? where official_account_id=?", clock.instant(), accountId);
        }
        List<Attempt> pending = jdbc.query("select * from session_binding_attempts where official_account_id=? and status in " + PENDING + " order by sequence_id", this::attempt, accountId);
        for (Attempt attempt : pending) {
            if (!attempt.expiresAt.isAfter(clock.instant())) terminate(attempt, "EXPIRED", "SESSION_BINDING_EXPIRED");
            else if (!valid(attempt)) terminate(attempt, "REJECTED", "SESSION_BINDING_CONTEXT_CHANGED");
        }
    }

    private boolean valid(Attempt attempt) {
        try {
            Context context = context(attempt.userId, attempt.enrollmentId, attempt.accountId, true);
            return attempt.teamId.equals(context.teamId) && attempt.teamVersion == context.teamVersion && attempt.revision.equals(context.revision);
        } catch (DomainException failure) { return false; }
    }

    private Context context(UUID userId, UUID enrollmentId, UUID expectedAccount, boolean lock) {
        String suffix = lock ? " for update" : "";
        List<UserContext> users = jdbc.query("select id,team_id,status from users where id=?" + suffix,
                (rs,n) -> new UserContext(uuid(rs,"team_id"), rs.getString("status")), userId);
        if (users.isEmpty() || !"ACTIVE".equals(users.get(0).status) || users.get(0).teamId == null) throw forbidden();
        if (jdbc.query("select id from meter_devices where id=? and user_id=? and status='ACTIVE' and expires_at>?" + suffix,
                (rs,n) -> uuid(rs,"id"), enrollmentId, userId, clock.instant()).isEmpty()) throw forbidden();
        List<TeamContext> teams = jdbc.query("select official_account_id,version from teams where id=? and status='ACTIVE'" + suffix,
                (rs,n) -> new TeamContext(uuid(rs,"official_account_id"), rs.getLong("version")), users.get(0).teamId);
        if (teams.isEmpty() || teams.get(0).accountId == null || (expectedAccount != null && !expectedAccount.equals(teams.get(0).accountId))) throw forbidden();
        UUID accountId = teams.get(0).accountId;
        List<UUID> revisions = jdbc.query("select revision from official_account_connections where official_account_id=? and status in ('CONNECTED','UNVERIFIED')" + suffix,
                (rs,n) -> uuid(rs,"revision"), accountId);
        if (revisions.isEmpty()) throw error("SESSION_BINDING_CONNECTION_UNAVAILABLE");
        // A duplicated local account record must never create a second queue for
        // the same external account identity.
        if (jdbc.queryForObject("select count(*) from official_account_connections where requested_account_id=(select requested_account_id from official_account_connections where official_account_id=?)", Integer.class, accountId) != 1)
            throw error("SESSION_BINDING_DUPLICATE_OFFICIAL_IDENTITY");
        return new Context(accountId, users.get(0).teamId, teams.get(0).version, revisions.get(0));
    }

    private void terminate(Attempt attempt, String status, String code) {
        boolean risky = Set.of("READY_FOR_LOGIN", "DETECTING", "AWAITING_CONFIRMATION").contains(attempt.status);
        jdbc.update("update session_binding_attempts set status=?,error_code=?,operation_id=null,operation_started_at=null where id=?", status, code, attempt.id);
        if (risky) jdbc.update("update session_binding_accounts set quarantined=true,reason=?,updated_at=? where official_account_id=?", code, clock.instant(), attempt.accountId);
        jdbc.update("update session_binding_accounts set active_attempt_id=null,updated_at=? where official_account_id=? and active_attempt_id=?", clock.instant(), attempt.accountId, attempt.id);
    }

    private void invalidateBindings(UUID accountId) {
        List<Attempt> bound = jdbc.query("select a.* from session_binding_attempts a join session_bindings b on b.attempt_id=a.id where b.official_account_id=? and b.status='ACTIVE'", this::attempt, accountId);
        for (Attempt attempt : bound) if (!valid(attempt)) {
            jdbc.update("update session_bindings set status='INVALIDATED',active_device_id=null,active_session_id=null,ended_at=? where attempt_id=? and status='ACTIVE'", clock.instant(), attempt.id);
            jdbc.update("update session_binding_attempts set status='REJECTED',error_code='SESSION_BINDING_CONTEXT_CHANGED' where id=? and status='BOUND'", attempt.id);
        }
    }

    private SessionBindingAttemptResponse response(Attempt attempt) {
        SessionBindingAccountResponse guard = accountStateUnlocked(attempt.accountId);
        int position = pending(attempt.status) ? jdbc.queryForObject("select count(*) from session_binding_attempts where official_account_id=? and status in " + PENDING + " and sequence_id<=?", Integer.class, attempt.accountId, attempt.sequence) : 0;
        List<SessionBindingResponse> bound = bindingRows("where b.attempt_id=?", attempt.id);
        return new SessionBindingAttemptResponse(attempt.id, attempt.deviceId, attempt.enrollmentId, attempt.accountId,
                attempt.status, position, "READY_FOR_LOGIN".equals(attempt.status) && !guard.quarantined(),
                attempt.candidateSessionId, attempt.candidateDisplayName, attempt.candidatePlatform, attempt.expiresAt,
                attempt.errorCode, guard.quarantined(), bound.isEmpty() ? null : bound.get(0));
    }

    private SessionBindingAccountResponse accountStateUnlocked(UUID accountId) {
        return jdbc.queryForObject("select * from session_binding_accounts where official_account_id=?", (rs,n) ->
                new SessionBindingAccountResponse(accountId, rs.getBoolean("quarantined"), rs.getString("reason"), uuid(rs,"active_attempt_id")), accountId);
    }

    private List<SessionBindingResponse> bindingRows(String where, Object... args) {
        return jdbc.query("select b.*,u.display_name,u.email from session_bindings b join users u on u.id=b.user_id " + where + " order by b.created_at desc,b.id", (rs,n) ->
                new SessionBindingResponse(uuid(rs,"id"), uuid(rs,"user_id"), rs.getString("display_name"), rs.getString("email"), uuid(rs,"device_id"), uuid(rs,"enrollment_id"), uuid(rs,"official_account_id"), rs.getString("session_id"), rs.getString("status"), rs.getString("match_method"), rs.getString("verification_level"), instant(rs,"created_at"), instant(rs,"ended_at")), args);
    }

    private UUID connectionRevision(UUID accountId) {
        List<UUID> ids = jdbc.query("select revision from official_account_connections where official_account_id=?", (rs,n) -> uuid(rs,"revision"), accountId);
        if (ids.isEmpty()) throw error("SESSION_BINDING_CONNECTION_UNAVAILABLE");
        return ids.get(0);
    }

    private Attempt load(UUID id) {
        List<Attempt> rows = jdbc.query("select * from session_binding_attempts where id=?", this::attempt, id);
        if (rows.isEmpty()) throw forbidden();
        return rows.get(0);
    }

    private Attempt attempt(ResultSet rs, int n) throws SQLException {
        return new Attempt(uuid(rs,"id"), rs.getLong("sequence_id"), uuid(rs,"user_id"), uuid(rs,"device_id"), uuid(rs,"enrollment_id"), uuid(rs,"team_id"), rs.getLong("team_version"), uuid(rs,"official_account_id"), uuid(rs,"connection_revision"), rs.getString("status"), instant(rs,"expires_at"), uuid(rs,"operation_id"), instant(rs,"operation_started_at"), rs.getString("before_session_ids"), rs.getString("official_account_key"), rs.getString("official_user_key"), rs.getString("candidate_session_id"), rs.getString("candidate_display_name"), rs.getString("candidate_platform"), instant(rs,"candidate_fetched_at"), rs.getString("error_code"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { var stamp = rs.getTimestamp(column); return stamp == null ? null : stamp.toInstant(); }
    private static boolean pending(String state) { return Set.of("QUEUED","PREPARING","READY_FOR_LOGIN","DETECTING","AWAITING_CONFIRMATION").contains(state); }
    private static String truncate(String text, int length) { return text == null ? null : text.substring(0, Math.min(text.length(), length)); }
    private DomainException forbidden() { return new DomainException("SESSION_BINDING_FORBIDDEN", "Session binding is unavailable for this user, enrollment or account", HttpStatus.FORBIDDEN); }
    private DomainException error(String code) { return new DomainException(code, "Session binding could not be safely completed", HttpStatus.CONFLICT); }
    private <T> T transaction(Supplier<T> work) { return tx.execute(ignored -> work.get()); }
    private record Context(UUID accountId, UUID teamId, long teamVersion, UUID revision) { }
    private record UserContext(UUID teamId, String status) { }
    private record TeamContext(UUID accountId, long version) { }
    private record Work(Attempt attempt, UUID operation, String status) { }
    private record Attempt(UUID id, long sequence, UUID userId, UUID deviceId, UUID enrollmentId, UUID teamId,
            long teamVersion, UUID accountId, UUID revision, String status, Instant expiresAt, UUID operationId,
            Instant operationStartedAt, String beforeSessionIds, String officialAccountKey, String officialUserKey,
            String candidateSessionId, String candidateDisplayName, String candidatePlatform, Instant candidateFetchedAt, String errorCode) { }
}
