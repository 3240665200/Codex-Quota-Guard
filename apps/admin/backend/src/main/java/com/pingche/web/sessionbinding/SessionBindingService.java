package com.pingche.web.sessionbinding;

import com.pingche.web.auth.DomainException;
import com.pingche.web.mail.MailAssistanceService;
import com.pingche.web.officialsession.OfficialConnectionRefreshResponse;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.officialsession.OfficialSessionInfo;
import com.pingche.web.officialsession.OfficialSessionLogoutFence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** A serial-login inference, never a cryptographic device attestation. */
@Service
public class SessionBindingService {
    private static final String PENDING = "('QUEUED','PREPARING','READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')";
    // Match OfficialConnectionService's per-account minimum refresh interval.
    private static final long CHECK_INTERVAL_SECONDS = 10;
    private final JdbcTemplate jdbc;
    private final OfficialConnectionService official;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final OfficialSessionLogoutFence logoutFence;
    private final MailAssistanceService mail;

    // Compatibility for isolated SQL tests without a mail subsystem.
    public SessionBindingService(JdbcTemplate jdbc, OfficialConnectionService official, Clock clock,
            ObjectMapper json, PlatformTransactionManager transactionManager) {
        this(jdbc, official, clock, json, transactionManager, null);
    }

    @Autowired
    public SessionBindingService(JdbcTemplate jdbc, OfficialConnectionService official, Clock clock,
            ObjectMapper json, PlatformTransactionManager transactionManager, MailAssistanceService mail) {
        this.jdbc = jdbc;
        this.official = official;
        this.clock = clock;
        this.json = json;
        this.tx = new TransactionTemplate(transactionManager);
        this.logoutFence = new OfficialSessionLogoutFence(jdbc);
        this.mail = mail;
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
            if (!existing.isEmpty()) {
                if (!existing.get(0).accountId.equals(accountId)) throw error("SESSION_BINDING_CONTEXT_CHANGED");
                return response(existing.get(0));
            }
            UUID id = UUID.randomUUID();
            jdbc.update("insert into session_binding_attempts (id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                    id, userId, deviceId, enrollmentId, current.teamId, current.teamVersion, accountId, current.revision, "QUEUED", at(clock.instant()), at(clock.instant().plusSeconds(3600)));
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
        UUID accountId = withAttempt(userId, id, () -> "QUEUED".equals(load(id).status) ? load(id).accountId : null);
        // Compatibility entry point only. The server scheduler progresses the
        // queue even when no client sends this request or reads current state.
        if (accountId != null) advance(accountId);
        return get(userId, id);
    }

    /** One bounded advancement. All official I/O happens outside database locks. */
    public void advance(UUID accountId) {
        Preparation preparation = transaction(() -> {
            lockAccount(accountId);
            sweep(accountId);
            Guard guard = guard(accountId);
            if (guard.recoveryId != null || future(guard.nextPrepareAt) || future(guard.settleUntil)) return null;
            if (guard.activeAttempt != null) {
                Attempt active = load(guard.activeAttempt);
                if (guard.quarantined || !Set.of("READY_FOR_LOGIN", "AWAITING_CONFIRMATION").contains(active.status)) return null;
                return new Preparation(reserveAfter(active), null);
            }
            List<Attempt> heads = jdbc.query("select * from session_binding_attempts where official_account_id=? and status='QUEUED' order by sequence_id limit 1", this::attempt, accountId);
            if (!heads.isEmpty()) {
                Attempt head = heads.get(0);
                UUID operation = UUID.randomUUID();
                long generation = guard.generation + 1;
                jdbc.update("update session_binding_accounts set active_attempt_id=?,generation=?,next_prepare_at=null,updated_at=? where official_account_id=?", head.id, generation, at(clock.instant()), accountId);
                jdbc.update("update session_binding_attempts set status='PREPARING',claim_generation=?,operation_id=?,operation_started_at=? where id=?", generation, operation, at(clock.instant()), head.id);
                return new Preparation(new Work(load(head.id), operation, "PREPARING"), null);
            }
            if (!guard.quarantined) {
                jdbc.update("update session_binding_accounts set next_prepare_at=null,prepare_failures=0,updated_at=? where official_account_id=?", at(clock.instant()), accountId);
                return null;
            }
            UUID recovery = UUID.randomUUID();
            long generation = guard.generation + 1;
            jdbc.update("update session_binding_accounts set generation=?,recovery_id=?,recovery_started_at=?,next_prepare_at=null,updated_at=? where official_account_id=?", generation, recovery, at(clock.instant()), at(clock.instant()), accountId);
            return new Preparation(null, new Recovery(accountId, recovery, generation, connectionRevision(accountId), clock.instant()));
        });
        if (preparation == null) return;
        if (preparation.work != null) {
            Work work = preparation.work;
            if ("DETECTING".equals(work.status)) {
                checkAutomatically(work);
                return;
            }
            try {
                OfficialConnectionRefreshResponse snapshot = fresh(work);
                finish(work, () -> grant(load(work.attempt.id), snapshot));
            } catch (DomainException failure) { retryPreparation(work, failure.getCode()); }
        } else recoverAutomatically(preparation.recovery);
    }

    private SessionBindingAttemptResponse grant(Attempt attempt, OfficialConnectionRefreshResponse snapshot) {
        jdbc.update("update session_binding_attempts set status='READY_FOR_LOGIN',before_session_ids=?,official_account_key=?,official_user_key=?,operation_id=null,operation_started_at=null,error_code=null,expires_at=? where id=?",
                json.writeValueAsString(snapshot.sessions().devices().stream().map(OfficialSessionInfo::sessionId).filter(value -> value != null && !value.isBlank()).toList()),
                snapshot.account().accountId(), snapshot.account().userId(), at(clock.instant().plusSeconds(1800)), attempt.id);
        clearRecovery(attempt.accountId);
        scheduleCheck(attempt.accountId);
        return response(load(attempt.id));
    }

    private void retryPreparation(Work work, String code) {
        finish(work, () -> {
            jdbc.update("update session_binding_attempts set status='QUEUED',error_code=?,operation_id=null,operation_started_at=null where id=?", code, work.attempt.id);
            jdbc.update("update session_binding_accounts set active_attempt_id=null,generation=generation+1,updated_at=? where official_account_id=?", at(clock.instant()), work.attempt.accountId);
            scheduleRetry(work.attempt.accountId);
            return response(load(work.attempt.id));
        });
    }

    private void recoverAutomatically(Recovery recovery) {
        try {
            OfficialConnectionRefreshResponse snapshot = official.refresh(recovery.accountId);
            validateSnapshot(snapshot, recovery.startedAt);
            String requested = jdbc.queryForObject("select requested_account_id from official_account_connections where official_account_id=?", String.class, recovery.accountId);
            if (!Objects.equals(requested, snapshot.account().accountId())) throw error("SESSION_BINDING_IDENTITY_CHANGED");
            transaction(() -> {
                lockAccount(recovery.accountId);
                if (owns(recovery) && recovery.revision.equals(connectionRevision(recovery.accountId))) clearRecovery(recovery.accountId);
                else if (owns(recovery)) { clearRecoveryClaim(recovery.accountId); scheduleRetry(recovery.accountId); }
                return null;
            });
        } catch (RuntimeException failure) {
            transaction(() -> {
                lockAccount(recovery.accountId);
                if (owns(recovery)) { clearRecoveryClaim(recovery.accountId); scheduleRetry(recovery.accountId); }
                return null;
            });
        }
    }

    private boolean owns(Recovery recovery) {
        Guard guard = guard(recovery.accountId);
        return recovery.id.equals(guard.recoveryId) && recovery.generation == guard.generation && guard.activeAttempt == null;
    }

    private void clearRecovery(UUID accountId) {
        jdbc.update("update session_binding_accounts set quarantined=false,reason=null,recovery_id=null,recovery_started_at=null,settle_until=null,next_prepare_at=null,prepare_failures=0,updated_at=? where official_account_id=?", at(clock.instant()), accountId);
    }

    private void clearRecoveryClaim(UUID accountId) {
        jdbc.update("update session_binding_accounts set recovery_id=null,recovery_started_at=null,generation=generation+1,updated_at=? where official_account_id=?", at(clock.instant()), accountId);
    }

    private void scheduleRetry(UUID accountId) {
        scheduleRetry(accountId, 5);
    }

    private void scheduleRetry(UUID accountId, long minimumSeconds) {
        Guard guard = guard(accountId);
        int failures = Math.min(guard.prepareFailures + 1, 16);
        long seconds = Math.min(minimumSeconds << Math.min(failures - 1, 4), 60L);
        jdbc.update("update session_binding_accounts set next_prepare_at=?,prepare_failures=?,updated_at=? where official_account_id=?", at(clock.instant().plusSeconds(seconds)), failures, at(clock.instant()), accountId);
    }

    private void scheduleCheck(UUID accountId) {
        jdbc.update("update session_binding_accounts set next_prepare_at=?,prepare_failures=0,updated_at=? where official_account_id=?",
                at(clock.instant().plusSeconds(CHECK_INTERVAL_SECONDS)), at(clock.instant()), accountId);
    }

    private void checkAutomatically(Work work) {
        try {
            OfficialConnectionRefreshResponse snapshot = fresh(work);
            finish(work, () -> identifyAutomatically(load(work.attempt.id), snapshot));
        } catch (DataIntegrityViolationException conflict) {
            fail(work, "SESSION_BINDING_CURRENT_CONFLICT");
        } catch (DomainException failure) {
            if (!Set.of("SESSION_BINDING_SNAPSHOT_UNAVAILABLE", "OFFICIAL_UNAVAILABLE", "OFFICIAL_RATE_LIMITED",
                    "OFFICIAL_REFRESH_RATE_LIMITED", "OFFICIAL_REFRESH_BUSY").contains(failure.getCode())) {
                fail(work, failure.getCode());
                return;
            }
            finish(work, () -> {
                Attempt attempt = load(work.attempt.id);
                jdbc.update("update session_binding_attempts set status=?,error_code=?,operation_id=null,operation_started_at=null where id=?",
                        attempt.candidateSessionId == null ? "READY_FOR_LOGIN" : "AWAITING_CONFIRMATION", failure.getCode(), attempt.id);
                scheduleRetry(attempt.accountId, CHECK_INTERVAL_SECONDS);
                return response(load(attempt.id));
            });
        }
    }

    private SessionBindingAttemptResponse identifyAutomatically(Attempt attempt, OfficialConnectionRefreshResponse snapshot) {
        Set<String> before = new HashSet<>();
        for (var id : json.readTree(attempt.beforeSessionIds)) before.add(id.asString());
        List<OfficialSessionInfo> candidates = snapshot.sessions().devices().stream()
                .filter(device -> device.sessionId() != null && !device.sessionId().isBlank())
                .filter(device -> !before.contains(device.sessionId())).toList();
        if (candidates.isEmpty() && attempt.candidateSessionId == null) {
            jdbc.update("update session_binding_attempts set status='READY_FOR_LOGIN',error_code=null,operation_id=null,operation_started_at=null where id=?", attempt.id);
            scheduleCheck(attempt.accountId);
        } else if (candidates.size() != 1 || (attempt.candidateSessionId != null && !attempt.candidateSessionId.equals(candidates.get(0).sessionId()))) {
            terminate(attempt, "REJECTED", candidates.isEmpty() ? "SESSION_BINDING_NO_NEW_SESSION" : "SESSION_BINDING_AMBIGUOUS");
        } else {
            OfficialSessionInfo candidate = candidates.get(0);
            if (candidate.applications().stream().noneMatch(app -> "Codex".equalsIgnoreCase(app.trim()))) {
                terminate(attempt, "REJECTED", "SESSION_BINDING_INVALID_CANDIDATE");
            } else if (attempt.candidateSessionId != null) {
                return bind(attempt, candidate.sessionId(), "AUTOMATIC_SERIAL_INFERENCE");
            } else {
                jdbc.update("update session_binding_attempts set status='AWAITING_CONFIRMATION',candidate_session_id=?,candidate_display_name=?,candidate_platform=?,candidate_fetched_at=?,error_code=null,operation_id=null,operation_started_at=null where id=?",
                        candidate.sessionId(), truncate(candidate.displayName(), 1000), truncate(candidate.platform(), 320), at(snapshot.fetchedAt()), attempt.id);
                scheduleCheck(attempt.accountId);
            }
        }
        return response(load(attempt.id));
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
                return bind(attempt, sessionId, "USER_CONFIRMED_INFERENCE");
            });
        } catch (DataIntegrityViolationException conflict) {
            // Database uniqueness remains authoritative across different account queues.
            return withAttempt(userId, id, () -> {
                terminate(load(id), "REJECTED", "SESSION_BINDING_CURRENT_CONFLICT");
                return response(load(id));
            });
        }
    }

    private SessionBindingAttemptResponse bind(Attempt attempt, String sessionId, String verificationLevel) {
        if (accountStateUnlocked(attempt.accountId).quarantined() || !owns(attempt) || !valid(attempt)) {
            terminate(attempt, "REJECTED", "SESSION_BINDING_CONTEXT_CHANGED");
            return response(load(attempt.id));
        }
        if (jdbc.queryForObject("select count(*) from session_bindings where active_device_id=? or (official_account_id=? and active_session_id=?)", Integer.class, attempt.deviceId, attempt.accountId, sessionId) > 0) {
            terminate(attempt, "REJECTED", "SESSION_BINDING_CURRENT_CONFLICT");
            return response(load(attempt.id));
        }
        jdbc.update("insert into session_bindings (id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), attempt.id, attempt.userId, attempt.deviceId, attempt.enrollmentId, attempt.teamId, attempt.teamVersion,
                attempt.accountId, attempt.revision, sessionId, attempt.deviceId, sessionId, "ACTIVE", "SERIAL_LOGIN_DIFF", verificationLevel, at(clock.instant()));
        if (mail != null) mail.endBindingClaim(attempt.userId, attempt.accountId);
        jdbc.update("update session_binding_attempts set status='BOUND',error_code=null,operation_id=null,operation_started_at=null where id=?", attempt.id);
        jdbc.update("update session_binding_accounts set active_attempt_id=null,generation=generation+1,next_prepare_at=null,prepare_failures=0,updated_at=? where official_account_id=? and active_attempt_id=?", at(clock.instant()), attempt.accountId, attempt.id);
        return response(load(attempt.id));
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
            jdbc.update("update session_bindings set status='ENDED',active_device_id=null,active_session_id=null,ended_at=? where id=? and user_id=? and status='ACTIVE'", at(clock.instant()), bindingId, userId);
            return null;
        });
    }

    /** Ends one local relationship without changing the upstream session or enrollment. */
    public void releaseForAdministrator(UUID bindingId, UUID accountId) {
        transaction(() -> {
            // Validate and lock the exact account/binding pair before checking
            // the fence shared with official logout operations.
            List<UUID> matchingAccounts = jdbc.query("select id from official_accounts where id=? and exists (select 1 from session_bindings where id=? and official_account_id=?) for update",
                    (rs, n) -> uuid(rs, "id"), accountId, bindingId, accountId);
            if (matchingAccounts.isEmpty()) throw bindingNotFound();
            lockAccount(accountId);
            List<UUID> attempts = jdbc.query("select attempt_id from session_bindings where id=? and official_account_id=?",
                    (rs, n) -> uuid(rs, "attempt_id"), bindingId, accountId);
            if (attempts.isEmpty()) throw bindingNotFound();
            int ended = jdbc.update("update session_bindings set status='ENDED',active_device_id=null,active_session_id=null,ended_at=? where id=? and official_account_id=? and status='ACTIVE'",
                    at(clock.instant()), bindingId, accountId);
            if (ended > 0) {
                // Older clients also use BOUND to restore the bound screen. Retain the
                // historical binding while preventing that attempt from granting it.
                jdbc.update("update session_binding_attempts set status='REJECTED',error_code='SESSION_BINDING_RELEASED' where id=? and official_account_id=? and status='BOUND'",
                        attempts.get(0), accountId);
            }
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

    /** Optional advanced override; ordinary cancellation recovers automatically after settling. */
    public SessionBindingAccountResponse recover(UUID accountId, boolean acknowledgeLateLoginRisk) {
        if (!acknowledgeLateLoginRisk) throw error("SESSION_BINDING_RECOVERY_ACK_REQUIRED");
        UUID recovery = UUID.randomUUID();
        UUID revision = transaction(() -> {
            lockAccount(accountId);
            sweep(accountId);
            if (accountStateUnlocked(accountId).activeAttemptId() != null) throw error("SESSION_BINDING_ACCOUNT_BUSY");
            jdbc.update("update session_binding_accounts set quarantined=true,reason='SESSION_BINDING_RECOVERY_IN_PROGRESS',generation=generation+1,recovery_id=?,recovery_started_at=?,updated_at=? where official_account_id=?", recovery, at(clock.instant()), at(clock.instant()), accountId);
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
                clearRecovery(accountId);
                return accountStateUnlocked(accountId);
            });
        } catch (RuntimeException failure) {
            transaction(() -> {
                lockAccount(accountId);
                if (recovery.equals(guard(accountId).recoveryId)) {
                    jdbc.update("update session_binding_accounts set reason='SESSION_BINDING_RECOVERY_FAILED',recovery_id=null,recovery_started_at=null,generation=generation+1,updated_at=? where official_account_id=?", at(clock.instant()), accountId);
                    scheduleRetry(accountId);
                }
                return null;
            });
            if (failure instanceof DomainException domain) throw domain;
            throw error("SESSION_BINDING_RECOVERY_FAILED");
        }
    }

    private Work reserveAfter(Attempt attempt) {
        if (!owns(attempt)) throw error("SESSION_BINDING_CONTEXT_CHANGED");
        UUID operation = UUID.randomUUID();
        jdbc.update("update session_binding_attempts set status='DETECTING',operation_id=?,operation_started_at=? where id=?", operation, at(clock.instant()), attempt.id);
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
                    candidate.sessionId(), truncate(candidate.displayName(), 1000), truncate(candidate.platform(), 320), at(snapshot.fetchedAt()), attempt.id);
        }
        return response(load(attempt.id));
    }

    private SessionBindingAttemptResponse finish(Work work, Supplier<SessionBindingAttemptResponse> completion) {
        return transaction(() -> {
            lockAccount(work.attempt.accountId);
            sweep(work.attempt.accountId);
            Attempt current = load(work.attempt.id);
            if (!work.operation.equals(current.operationId) || !work.status.equals(current.status)
                    || current.claimGeneration != work.attempt.claimGeneration || !owns(current)) return response(current);
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
        logoutFence.assertAvailable(accountId);
        if (jdbc.queryForObject("select count(*) from session_binding_accounts where official_account_id=?", Integer.class, accountId) == 0)
            jdbc.update("insert into session_binding_accounts (official_account_id,quarantined,updated_at) values (?,false,?)", accountId, at(clock.instant()));
    }

    private void sweep(UUID accountId) {
        Guard guard = guard(accountId);
        UUID active = guard.activeAttempt;
        if (active != null && jdbc.queryForObject("select count(*) from session_binding_attempts where id=? and official_account_id=? and status in ('PREPARING','READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')", Integer.class, active, accountId) == 0) {
            // Cascading deletion of a user must not erase the late-login fence.
            settle(accountId, "SESSION_BINDING_ORPHANED_ATTEMPT");
        }
        List<Attempt> pending = jdbc.query("select * from session_binding_attempts where official_account_id=? and status in " + PENDING + " order by sequence_id", this::attempt, accountId);
        for (Attempt attempt : pending) {
            if (!attempt.expiresAt.isAfter(clock.instant())) terminate(attempt, "EXPIRED", "SESSION_BINDING_EXPIRED");
            else if (!valid(attempt)) terminate(attempt, "REJECTED", "SESSION_BINDING_CONTEXT_CHANGED");
            else if (attempt.operationId != null && attempt.operationStartedAt != null
                    && !attempt.operationStartedAt.plusSeconds(90).isAfter(clock.instant())) {
                if ("PREPARING".equals(attempt.status) && owns(attempt)) {
                    jdbc.update("update session_binding_attempts set status='QUEUED',error_code='SESSION_BINDING_SNAPSHOT_UNAVAILABLE',operation_id=null,operation_started_at=null where id=?", attempt.id);
                    jdbc.update("update session_binding_accounts set active_attempt_id=null,generation=generation+1,updated_at=? where official_account_id=?", at(clock.instant()), accountId);
                    scheduleRetry(accountId);
                } else terminate(attempt, "REJECTED", "SESSION_BINDING_SNAPSHOT_UNAVAILABLE");
            }
        }
        guard = guard(accountId);
        if (guard.recoveryId != null && (guard.recoveryStartedAt == null
                || !guard.recoveryStartedAt.plusSeconds(90).isAfter(clock.instant()))) {
            clearRecoveryClaim(accountId);
            scheduleRetry(accountId);
        }
        // Old deployments required manual recovery. Upgrade them into the same
        // short settling + fresh-baseline path, never directly clear the guard.
        guard = guard(accountId);
        if (guard.quarantined && guard.settleUntil == null) {
            jdbc.update("update session_binding_accounts set settle_until=?,updated_at=? where official_account_id=?", at(clock.instant().plusSeconds(30)), at(clock.instant()), accountId);
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
                (rs,n) -> uuid(rs,"id"), enrollmentId, userId, at(clock.instant())).isEmpty()) throw forbidden();
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
        if (mail != null && Set.of("PREPARING", "READY_FOR_LOGIN", "DETECTING", "AWAITING_CONFIRMATION").contains(attempt.status)
                && owns(attempt)) mail.endBindingClaim(attempt.userId, attempt.accountId);
        boolean risky = Set.of("READY_FOR_LOGIN", "DETECTING", "AWAITING_CONFIRMATION").contains(attempt.status);
        jdbc.update("update session_binding_attempts set status=?,error_code=?,operation_id=null,operation_started_at=null where id=?", status, code, attempt.id);
        if (risky) settle(attempt.accountId, code);
        else jdbc.update("update session_binding_accounts set active_attempt_id=null,generation=generation+1,next_prepare_at=null,prepare_failures=0,updated_at=? where official_account_id=? and active_attempt_id=?", at(clock.instant()), attempt.accountId, attempt.id);
    }

    private void settle(UUID accountId, String code) {
        jdbc.update("update session_binding_accounts set quarantined=true,reason=?,active_attempt_id=null,generation=generation+1,recovery_id=null,recovery_started_at=null,settle_until=?,next_prepare_at=null,prepare_failures=0,updated_at=? where official_account_id=?", code, at(clock.instant().plusSeconds(30)), at(clock.instant()), accountId);
    }

    private boolean owns(Attempt attempt) {
        Guard guard = guard(attempt.accountId);
        return attempt.id.equals(guard.activeAttempt) && attempt.claimGeneration == guard.generation;
    }

    private void invalidateBindings(UUID accountId) {
        List<Attempt> bound = jdbc.query("select a.* from session_binding_attempts a join session_bindings b on b.attempt_id=a.id where b.official_account_id=? and b.status='ACTIVE'", this::attempt, accountId);
        for (Attempt attempt : bound) if (!valid(attempt)) {
            jdbc.update("update session_bindings set status='INVALIDATED',active_device_id=null,active_session_id=null,ended_at=? where attempt_id=? and status='ACTIVE'", at(clock.instant()), attempt.id);
            jdbc.update("update session_binding_attempts set status='REJECTED',error_code='SESSION_BINDING_CONTEXT_CHANGED' where id=? and status='BOUND'", attempt.id);
        }
    }

    private SessionBindingAttemptResponse response(Attempt attempt) {
        SessionBindingAccountResponse guard = accountStateUnlocked(attempt.accountId);
        int position = pending(attempt.status) ? jdbc.queryForObject("select count(*) from session_binding_attempts where official_account_id=? and status in " + PENDING + " and sequence_id<=?", Integer.class, attempt.accountId, attempt.sequence) : 0;
        List<SessionBindingResponse> bound = bindingRows("where b.attempt_id=?", attempt.id);
        return new SessionBindingAttemptResponse(attempt.id, attempt.deviceId, attempt.enrollmentId, attempt.accountId,
                attempt.status, position, "READY_FOR_LOGIN".equals(attempt.status) && !guard.quarantined() && owns(attempt),
                attempt.candidateSessionId, attempt.candidateDisplayName, attempt.candidatePlatform, attempt.expiresAt,
                attempt.errorCode, guard.quarantined(), bound.isEmpty() ? null : bound.get(0), guard.queueState(), guard.retryAt());
    }

    private SessionBindingAccountResponse accountStateUnlocked(UUID accountId) {
        Guard guard = guard(accountId);
        String state;
        Instant retryAt = null;
        if (guard.activeAttempt != null) {
            String status = jdbc.queryForObject("select status from session_binding_attempts where id=?", String.class, guard.activeAttempt);
            state = "PREPARING".equals(status) ? "PREPARING" : "ACTIVE";
        } else if (future(guard.settleUntil)) { state = "SETTLING"; retryAt = guard.settleUntil; }
        else if (guard.nextPrepareAt != null) { state = "RETRYING"; retryAt = guard.nextPrepareAt; }
        else if (guard.quarantined) { state = "SETTLING"; retryAt = guard.settleUntil; }
        else state = "IDLE";
        return new SessionBindingAccountResponse(accountId, guard.quarantined, guard.reason, guard.activeAttempt, state, retryAt);
    }

    private Guard guard(UUID accountId) {
        return jdbc.queryForObject("select * from session_binding_accounts where official_account_id=?", (rs,n) ->
                new Guard(uuid(rs,"active_attempt_id"),rs.getBoolean("quarantined"),rs.getString("reason"),rs.getLong("generation"),
                        instant(rs,"settle_until"),instant(rs,"next_prepare_at"),rs.getInt("prepare_failures"),uuid(rs,"recovery_id"),instant(rs,"recovery_started_at")), accountId);
    }

    private boolean future(Instant instant) { return instant != null && instant.isAfter(clock.instant()); }

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
        return new Attempt(uuid(rs,"id"), rs.getLong("sequence_id"), uuid(rs,"user_id"), uuid(rs,"device_id"), uuid(rs,"enrollment_id"), uuid(rs,"team_id"), rs.getLong("team_version"), uuid(rs,"official_account_id"), uuid(rs,"connection_revision"), rs.getString("status"), instant(rs,"expires_at"), uuid(rs,"operation_id"), instant(rs,"operation_started_at"), rs.getString("before_session_ids"), rs.getString("official_account_key"), rs.getString("official_user_key"), rs.getString("candidate_session_id"), rs.getString("candidate_display_name"), rs.getString("candidate_platform"), instant(rs,"candidate_fetched_at"), rs.getString("error_code"),rs.getLong("claim_generation"));
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
    private static OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private static Instant instant(ResultSet rs, String column) throws SQLException { var stamp = rs.getTimestamp(column); return stamp == null ? null : stamp.toInstant(); }
    private static boolean pending(String state) { return Set.of("QUEUED","PREPARING","READY_FOR_LOGIN","DETECTING","AWAITING_CONFIRMATION").contains(state); }
    private static String truncate(String text, int length) { return text == null ? null : text.substring(0, Math.min(text.length(), length)); }
    private DomainException forbidden() { return new DomainException("SESSION_BINDING_FORBIDDEN", "Session binding is unavailable for this user, enrollment or account", HttpStatus.FORBIDDEN); }
    private DomainException bindingNotFound() { return new DomainException("SESSION_BINDING_NOT_FOUND", "Session binding was not found for this account", HttpStatus.NOT_FOUND); }
    private DomainException error(String code) { return new DomainException(code, "Session binding could not be safely completed", HttpStatus.CONFLICT); }
    private <T> T transaction(Supplier<T> work) { return tx.execute(ignored -> work.get()); }
    private record Context(UUID accountId, UUID teamId, long teamVersion, UUID revision) { }
    private record UserContext(UUID teamId, String status) { }
    private record TeamContext(UUID accountId, long version) { }
    private record Work(Attempt attempt, UUID operation, String status) { }
    private record Preparation(Work work, Recovery recovery) { }
    private record Recovery(UUID accountId, UUID id, long generation, UUID revision, Instant startedAt) { }
    private record Guard(UUID activeAttempt, boolean quarantined, String reason, long generation, Instant settleUntil,
            Instant nextPrepareAt, int prepareFailures, UUID recoveryId, Instant recoveryStartedAt) { }
    private record Attempt(UUID id, long sequence, UUID userId, UUID deviceId, UUID enrollmentId, UUID teamId,
            long teamVersion, UUID accountId, UUID revision, String status, Instant expiresAt, UUID operationId,
            Instant operationStartedAt, String beforeSessionIds, String officialAccountKey, String officialUserKey,
            String candidateSessionId, String candidateDisplayName, String candidatePlatform, Instant candidateFetchedAt, String errorCode, long claimGeneration) { }
}
