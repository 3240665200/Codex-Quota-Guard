package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.ControlPlaneAuthSession;
import com.codexquotaguard.backend.controlplane.ControlPlaneException;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import com.codexquotaguard.backend.controlplane.PersonalQuotaOverview;
import com.codexquotaguard.backend.controlplane.SystemUser;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

/**
 * Keeps product access and refresh tokens only in this local Java process.
 *
 * <p>The WebView communicates through the Tauri allow-list and receives only
 * {@link SystemUser} or {@link PersonalQuotaOverview}; no controller exposes
 * this service's token-bearing state.</p>
 */
@Service
public final class SystemSessionService {

    private final ControlPlaneGateway controlPlane;
    private final Object lock = new Object();
    private Session session;

    public SystemSessionService(ControlPlaneGateway controlPlane) {
        if (controlPlane == null) {
            throw new IllegalArgumentException("Control-plane gateway is required.");
        }
        this.controlPlane = controlPlane;
    }

    public SystemSessionOverview login(String email, String password) {
        final ControlPlaneAuthSession authenticated;
        try {
            authenticated = controlPlane.login(email, password);
        } catch (ControlPlaneException exception) {
            throw translateLogin(exception);
        } catch (RuntimeException exception) {
            throw new ControlPlaneUnavailableException();
        }
        // Do not clear an existing usable session until a replacement has
        // successfully arrived from the central service.
        Session replacement = Session.from(authenticated);
        synchronized (lock) {
            session = replacement;
        }
        return SystemSessionOverview.signedIn(replacement.user());
    }

    /** Safe startup probe. A missing or revoked session is not an application error. */
    public SystemSessionOverview currentOverview() {
        try {
            return SystemSessionOverview.signedIn(current());
        } catch (SystemSessionRequiredException exception) {
            return SystemSessionOverview.signedOut();
        }
    }

    /** Requires a valid central product-user session. */
    public SystemUser current() {
        return invokeAuthenticated(controlPlane::current);
    }

    public PersonalQuotaOverview quotaOverview() {
        return invokeAuthenticated(controlPlane::quotaOverview);
    }

    /** Clears this process immediately, then makes a best-effort central logout. */
    public void logout() {
        Session previous;
        synchronized (lock) {
            previous = session;
            session = null;
        }
        if (previous == null) {
            return;
        }

        try {
            controlPlane.logout(previous.accessToken(), previous.refreshToken());
        } catch (ControlPlaneException exception) {
            // A short-lived access token may expire just before manual logout.
            // Refresh once to revoke the rotated refresh-token family too; no
            // refreshed value is ever reattached to the local session.
            if (exception.reason() == ControlPlaneException.Reason.UNAUTHORIZED) {
                try {
                    ControlPlaneAuthSession refreshed = controlPlane.refresh(previous.refreshToken());
                    controlPlane.logout(refreshed.accessToken(), refreshed.refreshToken());
                } catch (RuntimeException ignored) {
                    // Local logout must never leave a usable session behind.
                }
            }
        } catch (RuntimeException ignored) {
            // The in-process session is already cleared; never expose an
            // implementation exception through the local desktop API.
        }
    }

    @PreDestroy
    void clearOnShutdown() {
        synchronized (lock) {
            session = null;
        }
    }

    private <T> T invokeAuthenticated(TokenOperation<T> operation) {
        Session attempted = requireSession();
        try {
            T result = operation.call(attempted.accessToken());
            updateUserIfCurrent(attempted, extractUser(result));
            return result;
        } catch (ControlPlaneException exception) {
            if (exception.reason() != ControlPlaneException.Reason.UNAUTHORIZED) {
                throw translate(exception);
            }
        } catch (RuntimeException exception) {
            throw new ControlPlaneUnavailableException();
        }

        Session refreshed = refreshAfterUnauthorized(attempted);
        try {
            T result = operation.call(refreshed.accessToken());
            updateUserIfCurrent(refreshed, extractUser(result));
            return result;
        } catch (ControlPlaneException exception) {
            if (exception.reason() == ControlPlaneException.Reason.UNAUTHORIZED) {
                clearIfCurrent(refreshed);
                throw new SystemSessionRequiredException();
            }
            throw translate(exception);
        } catch (RuntimeException exception) {
            throw new ControlPlaneUnavailableException();
        }
    }

    private static SystemUser extractUser(Object result) {
        if (result instanceof SystemUser user) {
            return user;
        }
        if (result instanceof PersonalQuotaOverview overview) {
            return overview.user();
        }
        throw new ControlPlaneUnavailableException();
    }

    private void updateUserIfCurrent(Session source, SystemUser user) {
        if (user == null) {
            return;
        }
        synchronized (lock) {
            // A slow response from an older login must never overwrite the
            // identity attached to a newer session.
            if (session == source) {
                session = source.withUser(user);
            }
        }
    }

    private Session requireSession() {
        synchronized (lock) {
            if (session == null) {
                throw new SystemSessionRequiredException();
            }
            return session;
        }
    }

    private Session refreshAfterUnauthorized(Session rejected) {
        synchronized (lock) {
            if (session == null) {
                throw new SystemSessionRequiredException();
            }
            if (session != rejected) {
                return session;
            }
            try {
                session = Session.from(controlPlane.refresh(rejected.refreshToken()));
                return session;
            } catch (ControlPlaneException exception) {
                if (exception.reason() == ControlPlaneException.Reason.UNAUTHORIZED) {
                    session = null;
                    throw new SystemSessionRequiredException();
                }
                throw translate(exception);
            } catch (RuntimeException exception) {
                throw new ControlPlaneUnavailableException();
            }
        }
    }

    private void clearIfCurrent(Session rejected) {
        synchronized (lock) {
            if (session == rejected) {
                session = null;
            }
        }
    }

    private static RuntimeException translate(ControlPlaneException exception) {
        return switch (exception.reason()) {
            case UNAUTHORIZED -> new SystemSessionRequiredException();
            case INVALID_REQUEST, UNAVAILABLE -> new ControlPlaneUnavailableException();
        };
    }

    private static RuntimeException translateLogin(ControlPlaneException exception) {
        return switch (exception.reason()) {
            case UNAUTHORIZED, INVALID_REQUEST -> new LoginDeniedException();
            case UNAVAILABLE -> new ControlPlaneUnavailableException();
        };
    }

    @FunctionalInterface
    private interface TokenOperation<T> {
        T call(String accessToken);
    }

    private static final class Session {
        private final String accessToken;
        private final String refreshToken;
        private final SystemUser user;

        private Session(String accessToken, String refreshToken, SystemUser user) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.user = user;
        }

        static Session from(ControlPlaneAuthSession session) {
            return new Session(session.accessToken(), session.refreshToken(), session.user());
        }

        String accessToken() {
            return accessToken;
        }

        String refreshToken() {
            return refreshToken;
        }

        SystemUser user() {
            return user;
        }

        Session withUser(SystemUser replacement) {
            return new Session(accessToken, refreshToken, replacement);
        }
    }
}
