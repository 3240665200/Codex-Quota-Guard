package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.SystemLogoutEvent;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Bounded invalidation streams; the validated bearer is discarded after subscription. */
@Service
public class LoginAssistanceEvents implements DisposableBean {
    private final JwtService jwt;
    private final UserRepository users;
    private final LoginAssistanceState state;
    private final Clock clock;
    private final Supplier<SseEmitter> emitters;
    private final Map<SseEmitter, Subscription> streams = new HashMap<>();
    private final ScheduledExecutorService scheduler;
    private boolean closed;

    @Autowired
    public LoginAssistanceEvents(JwtService jwt, UserRepository users, LoginAssistanceState state, Clock clock) {
        this(jwt, users, state, clock, () -> new SseEmitter(0L), true);
    }

    LoginAssistanceEvents(JwtService jwt, UserRepository users, LoginAssistanceState state, Clock clock,
            Supplier<SseEmitter> emitters, boolean scheduled) {
        this.jwt = jwt; this.users = users; this.state = state; this.clock = clock; this.emitters = emitters;
        scheduler = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "login-assistance-events"); thread.setDaemon(true); return thread;
        });
        if (scheduled) scheduler.scheduleWithFixedDelay(this::tick, 1, 1, TimeUnit.SECONDS);
    }

    public synchronized SseEmitter subscribe(UUID authenticatedUserId, String bearer) {
        JwtService.JwtIdentity identity;
        try { identity = jwt.parseIdentity(bearer); }
        catch (RuntimeException ignored) { throw unauthorized(); }
        User user = users.findById(authenticatedUserId).orElse(null);
        Instant now = clock.instant();
        if (identity == null || !identity.userId().equals(authenticatedUserId) || !valid(user, identity.sessionVersion())
                || identity.expiresAt() == null || !identity.expiresAt().isAfter(now)) throw unauthorized();
        if (closed) throw new DomainException("ASSISTANCE_EVENTS_UNAVAILABLE", "State stream is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        if (streams.size() >= 128 || streams.values().stream().filter(s -> s.userId.equals(authenticatedUserId)).count() >= 2)
            throw new DomainException("ASSISTANCE_EVENTS_CAPACITY", "State stream capacity is full", HttpStatus.TOO_MANY_REQUESTS);
        String fingerprint = state.fingerprint(authenticatedUserId);
        SseEmitter emitter = emitters.get();
        Subscription subscription = new Subscription(authenticatedUserId, identity.sessionVersion(), identity.expiresAt(), emitter, fingerprint, now);
        streams.put(emitter, subscription);
        emitter.onCompletion(() -> remove(emitter));
        emitter.onTimeout(() -> remove(emitter));
        emitter.onError(ignored -> remove(emitter));
        send(subscription, "connected");
        return emitter;
    }

    synchronized void tick() {
        if (closed || streams.isEmpty()) return;
        Instant now = clock.instant();
        Map<UUID, OwnerState> snapshots = new HashMap<>();
        for (Subscription subscription : new ArrayList<>(streams.values())) {
            if (!subscription.expiresAt.isAfter(now)) { finish(subscription); continue; }
            OwnerState snapshot = snapshots.computeIfAbsent(subscription.userId, id -> {
                try {
                    User user = users.findById(id).orElse(null);
                    return new OwnerState(user, user == null ? null : state.fingerprint(id));
                } catch (RuntimeException ignored) { return new OwnerState(null, null); }
            });
            if (!valid(snapshot.user, subscription.sessionVersion)) { finish(subscription); continue; }
            if (!subscription.fingerprint.equals(snapshot.fingerprint)) {
                subscription.fingerprint = snapshot.fingerprint;
                if (!send(subscription, "state_changed")) continue;
            }
            if (!subscription.heartbeatAt.plusSeconds(15).isAfter(now)) {
                subscription.heartbeatAt = now;
                send(subscription, "heartbeat");
            }
        }
    }

    private boolean valid(User user, long version) {
        return user != null && user.getStatus() == User.Status.ACTIVE && user.getSessionVersion() == version;
    }

    private boolean send(Subscription subscription, String name) {
        try {
            subscription.emitter.send(SseEmitter.event().name(name).data(Map.of(), MediaType.APPLICATION_JSON));
            return true;
        } catch (IOException ignored) {
            // The servlet container owns IOException completion; release our capacity immediately.
            remove(subscription.emitter);
            return false;
        } catch (RuntimeException ignored) {
            finish(subscription);
            return false;
        }
    }

    private void finish(Subscription subscription) {
        remove(subscription.emitter);
        try { subscription.emitter.complete(); } catch (RuntimeException ignored) { }
    }

    private synchronized void remove(SseEmitter emitter) { streams.remove(emitter); }
    synchronized int subscriptionCount() { return streams.size(); }

    @EventListener
    public synchronized void systemLogout(SystemLogoutEvent event) {
        for (Subscription subscription : new ArrayList<>(streams.values()))
            if (subscription.userId.equals(event.userId())) finish(subscription);
    }

    @Override public synchronized void destroy() {
        closed = true; scheduler.shutdownNow();
        for (Subscription subscription : new ArrayList<>(streams.values())) finish(subscription);
    }

    private DomainException unauthorized() {
        return new DomainException("AUTH_TOKEN_INVALID", "Access token is invalid or expired", HttpStatus.UNAUTHORIZED);
    }

    private record OwnerState(User user, String fingerprint) { }
    private static final class Subscription {
        final UUID userId;
        final long sessionVersion;
        final Instant expiresAt;
        final SseEmitter emitter;
        String fingerprint;
        Instant heartbeatAt;
        Subscription(UUID userId, long sessionVersion, Instant expiresAt, SseEmitter emitter, String fingerprint, Instant heartbeatAt) {
            this.userId = userId; this.sessionVersion = sessionVersion; this.expiresAt = expiresAt;
            this.emitter = emitter; this.fingerprint = fingerprint; this.heartbeatAt = heartbeatAt;
        }
    }
}
