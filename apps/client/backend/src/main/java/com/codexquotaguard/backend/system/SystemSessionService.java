package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.ControlPlaneAuthSession;
import com.codexquotaguard.backend.controlplane.ControlPlaneException;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import com.codexquotaguard.backend.controlplane.LoginAssistanceInfo;
import com.codexquotaguard.backend.controlplane.LoginAssistanceListener;
import com.codexquotaguard.backend.controlplane.LoginAssistanceCurrent;
import com.codexquotaguard.backend.controlplane.MeterEnrollment;
import com.codexquotaguard.backend.controlplane.SessionBindingAttempt;
import com.codexquotaguard.backend.controlplane.SessionBindingCurrent;
import com.codexquotaguard.backend.controlplane.OfficialCodexRateLimits;
import com.codexquotaguard.backend.controlplane.PersonalQuotaOverview;
import com.codexquotaguard.backend.controlplane.PersonalUsageAnalytics;
import com.codexquotaguard.backend.controlplane.SystemUser;
import com.codexquotaguard.backend.controlplane.UserUsageEventPage;
import com.codexquotaguard.backend.usage.CodexUsageEvent;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Keeps access tokens in memory and only the sealed refresh token on disk. */
@Service
public final class SystemSessionService {
    private static final Duration REMEMBERED_LIFETIME = Duration.ofDays(7);
    private final ControlPlaneGateway controlPlane;
    private final SessionStorage storage;
    private final Clock clock;
    private final Object lock = new Object();
    private final ReentrantLock transitions = new ReentrantLock(true);
    private Session session;
    private long loginGeneration;
    private long credentialVersion;
    private boolean restoreAllowed = true;
    private boolean closed;

    public SystemSessionService(ControlPlaneGateway controlPlane) {
        this(controlPlane, SessionStorageConfiguration.create("", "", false, "http://127.0.0.1"), Clock.systemUTC());
    }

    @Autowired
    public SystemSessionService(ControlPlaneGateway controlPlane, SessionStorage storage, Clock clock) {
        if (controlPlane == null || storage == null || clock == null) throw new IllegalArgumentException("Complete session configuration is required.");
        this.controlPlane = controlPlane;
        this.storage = storage;
        this.clock = clock;
    }

    public SystemSessionOverview login(String email, String password) { return loginProbe(email, password).overview(); }

    Probe loginProbe(String email, String password) {
        final long attempt;
        final Instant loginAt = clock.instant();
        synchronized (lock) {
            ensureStorage();
            attempt = ++loginGeneration;
        }
        transitions.lock();
        ControlPlaneAuthSession authenticated = null;
        try {
            checkEpoch(attempt);
            try { authenticated = controlPlane.login(email, password); }
            catch (ControlPlaneException failure) { throw translateLogin(failure); }
            catch (RuntimeException failure) { throw new ControlPlaneUnavailableException(); }
            if (!activeUser(authenticated.user(), null)) throw new LoginDeniedException();
            SavedSession remembered = new SavedSession(authenticated.refreshToken(), authenticated.user().id(), loginAt,
                    limitedDeadline(loginAt.plus(REMEMBERED_LIFETIME), loginAt, authenticated.refreshExpiresIn()));
            return commit(authenticated, remembered, attempt, true);
        } catch (SessionStorageException | SystemSessionRequiredException | LoginDeniedException failure) {
            if (authenticated != null) revoke(authenticated.accessToken(), authenticated.refreshToken(), false);
            throw failure;
        } finally { transitions.unlock(); }
    }

    public SystemSessionOverview currentOverview() { return currentProbe().overview(); }

    Probe currentProbe() {
        try {
            restoreIfNeeded();
            Result<SystemUser> current = invoke(controlPlane::current, null);
            return new Probe(SystemSessionOverview.signedIn(current.value()), current.generation());
        } catch (SystemSessionRequiredException signedOut) {
            synchronized (lock) { return new Probe(SystemSessionOverview.signedOut(), loginGeneration); }
        }
    }

    public SystemUser current() { restoreIfNeeded(); return invoke(controlPlane::current, null).value(); }
    public com.codexquotaguard.backend.controlplane.ClientAccess clientAccess() {
        return invoke(controlPlane::clientAccess, null).value();
    }
    public PersonalQuotaOverview quotaOverview() { return invoke(controlPlane::quotaOverview, null).value(); }
    public LoginAssistanceInfo loginAssistanceInfo() { return invoke(controlPlane::loginAssistanceInfo, null).value(); }
    public LoginAssistanceCurrent currentLoginAssistance() { return invoke(controlPlane::currentLoginAssistance, null).value(); }
    public LoginAssistanceListener startLoginAssistance() { return invoke(controlPlane::startLoginAssistance, null).value(); }
    public LoginAssistanceListener loginAssistanceStatus(UUID listenerId) { return invoke(token -> controlPlane.loginAssistanceStatus(token, listenerId), null).value(); }
    public LoginAssistanceListener cancelLoginAssistance(UUID listenerId) { return invoke(token -> controlPlane.cancelLoginAssistance(token, listenerId), null).value(); }
    public UserUsageEventPage usageEvents(int page, int size) { return invoke(token -> controlPlane.usageEvents(token, page, size), null).value(); }
    public PersonalUsageAnalytics usageAnalytics(int days) { return invoke(token -> controlPlane.usageAnalytics(token, days), null).value(); }
    public MeterEnrollment enrollMeter(UUID deviceId) { return invoke(token -> controlPlane.enrollMeter(token, deviceId), null).value(); }
    /** Runs backend-only work with the same refresh, user and generation checks as other product APIs. */
    public <T> T withAuthenticatedToken(java.util.function.Function<String, T> operation) {
        return invoke(operation::apply, null).value();
    }
    public SessionBindingCurrent sessionBindingCurrent(UUID deviceId, UUID enrollmentId) {
        return invoke(token -> controlPlane.sessionBindingCurrent(token, deviceId, enrollmentId), null).value();
    }
    public SessionBindingAttempt startSessionBinding(UUID deviceId, UUID enrollmentId) {
        return invoke(token -> controlPlane.startSessionBinding(token, deviceId, enrollmentId), null).value();
    }
    public SessionBindingAttempt sessionBindingAttempt(UUID attemptId) {
        return invoke(token -> controlPlane.sessionBindingAttempt(token, attemptId), null).value();
    }
    public SessionBindingAttempt sessionBindingAction(UUID attemptId, String action, String candidateSessionId) {
        return invoke(token -> controlPlane.sessionBindingAction(token, attemptId, action, candidateSessionId), null).value();
    }
    MeterEnrollment enrollMeter(UUID deviceId, Probe expected) {
        requireCurrent(expected);
        MeterEnrollment result = invoke(token -> controlPlane.enrollMeter(token, deviceId), expected.overview().user().id()).value();
        requireCurrent(expected);
        return result;
    }
    public int reportUsage(UUID expectedUserId, List<CodexUsageEvent> events) {
        return invoke(token -> controlPlane.reportUsage(token, events), expectedUserId).value();
    }
    public void reportOfficialQuota(OfficialCodexRateLimits snapshot) {
        invoke(token -> { controlPlane.reportOfficialQuota(token, snapshot); return null; }, null);
    }

    /** Passive scanner attribution never restores an unverified saved identity. */
    public Optional<UUID> localUserId() {
        synchronized (lock) {
            expireSession();
            return session == null ? Optional.empty() : Optional.of(session.user().id());
        }
    }
    /** Passive local identity check for long-running helpers; never restores or exposes credentials. */
    public Optional<SystemUser> localUser() {
        synchronized (lock) {
            expireSession();
            return session == null ? Optional.empty() : Optional.of(session.user());
        }
    }
    public UUID requireLocalUserId() { synchronized (lock) { return requireSession().user().id(); } }
    long generation() { synchronized (lock) { return loginGeneration; } }
    Probe pairingProbe() {
        synchronized (lock) { return new Probe(SystemSessionOverview.signedIn(requireSession().user()), loginGeneration); }
    }
    boolean matches(Probe probe) {
        synchronized (lock) {
            expireSession();
            return session != null && !closed && loginGeneration == probe.generation()
                    && probe.overview().user() != null && session.user().id().equals(probe.overview().user().id());
        }
    }
    void requireCurrent(Probe probe) { if (!matches(probe)) throw new SystemSessionRequiredException(); }

    /** Holds the identity epoch lock across the short, local publication step. */
    void withCurrentIdentity(Probe probe, Runnable publish) {
        synchronized (lock) {
            requireCurrent(probe);
            publish.run();
        }
    }

    public void logout() { logout(null); }
    void logoutIfGeneration(long generation) { logout(generation); }

    private void logout(Long expected) {
        Session previous;
        SavedSession unrestored = null;
        SessionStorageException storageFailure = null;
        synchronized (lock) {
            if (expected != null && expected != loginGeneration) return;
            previous = session;
            session = null;
            loginGeneration++;
            restoreAllowed = false;
            try {
                ensureStorage();
                if (previous == null) unrestored = storage.load().orElse(null);
                storage.clear();
            } catch (SessionStorageException failure) { storageFailure = failure; }
        }
        if (previous != null) {
            transitions.lock();
            try { revoke(previous.accessToken(), previous.refreshToken(), true); }
            finally { transitions.unlock(); }
        } else if (unrestored != null && !transitions.isHeldByCurrentThread()) {
            transitions.lock();
            try {
                ControlPlaneAuthSession token = controlPlane.refresh(unrestored.refreshToken());
                if (token != null) revoke(token.accessToken(), token.refreshToken(), false);
            } catch (RuntimeException ignored) { }
            finally { transitions.unlock(); }
        }
        if (storageFailure != null) throw storageFailure;
    }

    @PreDestroy
    void clearOnShutdown() {
        // Finish any serialized rotation's durable commit before releasing the
        // process lock to a replacement app. Closing itself is not logout.
        transitions.lock();
        try {
            synchronized (lock) { closed = true; loginGeneration++; session = null; }
            storage.close();
        } finally { transitions.unlock(); }
    }

    private void restoreIfNeeded() {
        synchronized (lock) {
            ensureStorage();
            expireSession();
            if (session != null || !restoreAllowed) return;
        }
        transitions.lock();
        ControlPlaneAuthSession restored = null;
        long attempt = -1;
        try {
            SavedSession saved;
            synchronized (lock) {
                ensureStorage();
                expireSession();
                if (session != null || !restoreAllowed) return;
                saved = storage.load().orElse(null);
                if (saved == null) return;
                if (!saved.expiresAt().isAfter(clock.instant()) || saved.loginAt().isAfter(clock.instant())) {
                    storage.clear();
                    restoreAllowed = false;
                    loginGeneration++;
                    return;
                }
                attempt = ++loginGeneration;
            }
            Instant requestAt = clock.instant();
            try { restored = controlPlane.refresh(saved.refreshToken()); }
            catch (ControlPlaneException failure) {
                if (failure.reason() == ControlPlaneException.Reason.UNAUTHORIZED || failure.reason() == ControlPlaneException.Reason.ACCESS_DENIED) {
                    invalidate(attempt);
                    throw new SystemSessionRequiredException();
                }
                throw translate(failure);
            } catch (RuntimeException failure) { throw new ControlPlaneUnavailableException(); }
            if (!activeUser(restored.user(), saved.userId())) {
                invalidate(attempt);
                throw new SystemSessionRequiredException();
            }
            SavedSession rotated = new SavedSession(restored.refreshToken(), saved.userId(), saved.loginAt(),
                    limitedDeadline(saved.expiresAt(), requestAt, restored.refreshExpiresIn()));
            commit(restored, rotated, attempt, true);
        } catch (SessionStorageException | SystemSessionRequiredException failure) {
            if (restored != null) revoke(restored.accessToken(), restored.refreshToken(), false);
            throw failure;
        } finally { transitions.unlock(); }
    }

    private <T> Result<T> invoke(TokenOperation<T> operation, UUID expectedUser) {
        Session attempted;
        long epoch;
        synchronized (lock) {
            attempted = requireSession();
            if (expectedUser != null && !expectedUser.equals(attempted.user().id())) throw new SystemSessionRequiredException();
            epoch = loginGeneration;
        }
        try { return finish(operation.call(attempted.accessToken()), attempted, epoch); }
        catch (ControlPlaneException failure) {
            if (failure.reason() != ControlPlaneException.Reason.UNAUTHORIZED) throw translate(failure);
        } catch (SessionStorageException | SystemSessionRequiredException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ControlPlaneUnavailableException(); }

        Session refreshed = refresh(attempted, epoch);
        try { return finish(operation.call(refreshed.accessToken()), refreshed, epoch); }
        catch (ControlPlaneException failure) {
            if (failure.reason() == ControlPlaneException.Reason.UNAUTHORIZED || failure.reason() == ControlPlaneException.Reason.ACCESS_DENIED) {
                invalidate(epoch);
                throw new SystemSessionRequiredException();
            }
            throw translate(failure);
        } catch (SessionStorageException | SystemSessionRequiredException failure) { throw failure; }
        catch (RuntimeException failure) { throw new ControlPlaneUnavailableException(); }
    }

    private Session refresh(Session rejected, long epoch) {
        transitions.lock();
        ControlPlaneAuthSession renewed = null;
        try {
            Session previous;
            synchronized (lock) {
                checkEpoch(epoch);
                previous = requireSession();
                if (!previous.user().id().equals(rejected.user().id())) throw new SystemSessionRequiredException();
                // Metadata updates never change this credential version.
                if (previous.version() != rejected.version()) return previous;
            }
            Instant requestAt = clock.instant();
            try { renewed = controlPlane.refresh(previous.refreshToken()); }
            catch (ControlPlaneException failure) {
                if (failure.reason() == ControlPlaneException.Reason.UNAUTHORIZED || failure.reason() == ControlPlaneException.Reason.ACCESS_DENIED) {
                    invalidate(epoch);
                    throw new SystemSessionRequiredException();
                }
                throw translate(failure);
            } catch (RuntimeException failure) { throw new ControlPlaneUnavailableException(); }
            if (!activeUser(renewed.user(), previous.user().id())) {
                invalidate(epoch);
                throw new SystemSessionRequiredException();
            }
            SavedSession remembered = new SavedSession(renewed.refreshToken(), previous.user().id(), previous.saved().loginAt(),
                    limitedDeadline(previous.saved().expiresAt(), requestAt, renewed.refreshExpiresIn()));
            commit(renewed, remembered, epoch, false);
            synchronized (lock) { checkEpoch(epoch); return requireSession(); }
        } catch (SessionStorageException | SystemSessionRequiredException failure) {
            if (renewed != null) revoke(renewed.accessToken(), renewed.refreshToken(), false);
            throw failure;
        } finally { transitions.unlock(); }
    }

    private Probe commit(ControlPlaneAuthSession authenticated, SavedSession remembered, long attempt, boolean newLogin) {
        synchronized (lock) {
            checkEpoch(attempt);
            if (!remembered.expiresAt().isAfter(clock.instant())) {
                invalidate(attempt);
                throw new SystemSessionRequiredException();
            }
            try { storage.save(remembered); }
            catch (SessionStorageException failure) {
                session = null;
                restoreAllowed = false;
                loginGeneration++;
                try { storage.clear(); } catch (SessionStorageException ignored) { }
                throw failure;
            }
            if (newLogin) loginGeneration++;
            session = new Session(authenticated.accessToken(), authenticated.refreshToken(), authenticated.user(), remembered, ++credentialVersion);
            restoreAllowed = true;
            return new Probe(SystemSessionOverview.signedIn(session.user()), loginGeneration);
        }
    }

    private <T> Result<T> finish(T result, Session attempted, long epoch) {
        synchronized (lock) {
            checkEpoch(epoch);
            Session active = requireSession();
            SystemUser user = extractUser(result);
            if (!active.user().id().equals(attempted.user().id()) || (user != null && !activeUser(user, active.user().id()))) {
                invalidate(epoch);
                throw new SystemSessionRequiredException();
            }
            if (user != null && active.version() == attempted.version()) session = active.withUser(user);
            return new Result<>(result, epoch);
        }
    }

    private void invalidate(long expected) {
        synchronized (lock) {
            if (loginGeneration != expected) return;
            session = null;
            restoreAllowed = false;
            loginGeneration++;
            storage.clear();
        }
    }
    private Session requireSession() {
        expireSession();
        if (closed || session == null) throw new SystemSessionRequiredException();
        return session;
    }
    private void expireSession() {
        if (session != null && (!session.saved().expiresAt().isAfter(clock.instant()) || session.saved().loginAt().isAfter(clock.instant()))) {
            session = null;
            restoreAllowed = false;
            loginGeneration++;
            storage.clear();
        }
    }
    private void ensureStorage() {
        if (closed) throw new SessionStorageException();
        storage.ensureAvailable();
    }
    private void checkEpoch(long expected) {
        synchronized (lock) { if (closed || expected != loginGeneration) throw new SystemSessionRequiredException(); }
    }
    private static Instant limitedDeadline(Instant original, Instant requestAt, long ttl) {
        Instant serverLimit = requestAt.plusSeconds(Math.min(ttl, REMEMBERED_LIFETIME.toSeconds()));
        return original.isBefore(serverLimit) ? original : serverLimit;
    }
    private static boolean activeUser(SystemUser user, UUID expected) {
        return user != null && "ACTIVE".equals(user.status()) && (expected == null || expected.equals(user.id()));
    }
    private static SystemUser extractUser(Object result) {
        if (result instanceof SystemUser user) return user;
        if (result instanceof PersonalQuotaOverview overview) return overview.user();
        return null;
    }
    private void revoke(String access, String refresh, boolean refreshExpiredAccess) {
        try { controlPlane.logout(access, refresh); }
        catch (ControlPlaneException failure) {
            if (refreshExpiredAccess && failure.reason() == ControlPlaneException.Reason.UNAUTHORIZED) {
                try {
                    ControlPlaneAuthSession renewed = controlPlane.refresh(refresh);
                    controlPlane.logout(renewed.accessToken(), renewed.refreshToken());
                } catch (RuntimeException ignored) { }
            }
        } catch (RuntimeException ignored) { }
    }
    private static RuntimeException translate(ControlPlaneException failure) {
        return switch (failure.reason()) {
            case UNAUTHORIZED -> new SystemSessionRequiredException();
            case ACCESS_DENIED -> new CodexAccessDeniedException();
            case BINDING_REQUIRED -> new SessionBindingRequiredException();
            case INVALID_REQUEST -> new ControlPlaneRequestRejectedException();
            case UNAVAILABLE -> new ControlPlaneUnavailableException();
            case MAIL_BUSY, MAIL_RATE_LIMITED, MAIL_REJECTED -> new LoginAssistanceException(failure.reason());
        };
    }
    private static RuntimeException translateLogin(ControlPlaneException failure) {
        return switch (failure.reason()) {
            case UNAUTHORIZED, INVALID_REQUEST -> new LoginDeniedException();
            default -> new ControlPlaneUnavailableException();
        };
    }

    record Probe(SystemSessionOverview overview, long generation) { }
    private record Result<T>(T value, long generation) { }
    private record Session(String accessToken, String refreshToken, SystemUser user, SavedSession saved, long version) {
        Session withUser(SystemUser replacement) { return new Session(accessToken, refreshToken, replacement, saved, version); }
        @Override public String toString() { return "Session[redacted]"; }
    }
    @FunctionalInterface private interface TokenOperation<T> { T call(String accessToken); }
}
