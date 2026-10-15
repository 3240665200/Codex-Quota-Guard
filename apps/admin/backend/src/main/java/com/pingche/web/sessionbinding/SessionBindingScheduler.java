package com.pingche.web.sessionbinding;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Bounded server-side progression; the database claim is authoritative across instances. */
@Component
public final class SessionBindingScheduler implements AutoCloseable {
    private final JdbcTemplate jdbc;
    private final SessionBindingService service;
    private final boolean enabled;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean started = new AtomicBoolean();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "binding-queue-timer"); thread.setDaemon(true); return thread;
    });
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> {
                Thread thread = new Thread(task, "binding-queue-worker"); thread.setDaemon(true); return thread;
            });
    private UUID after;

    public SessionBindingScheduler(JdbcTemplate jdbc, SessionBindingService service,
            @Value("${app.session-binding-scheduler-enabled:${SESSION_BINDING_SCHEDULER_ENABLED:true}}") boolean enabled) {
        this.jdbc = jdbc;
        this.service = service;
        this.enabled = enabled;
    }

    @PostConstruct public void start() {
        if (enabled && started.compareAndSet(false, true)) timer.scheduleWithFixedDelay(this::tick, 0, 1, TimeUnit.SECONDS);
    }

    private void tick() {
        try {
            String suffix = after == null ? "" : " and a.id>?";
            String sql = "select a.id from official_accounts a where (exists (select 1 from session_binding_accounts g where g.official_account_id=a.id and (g.quarantined=true or g.active_attempt_id is not null or g.next_prepare_at is not null)) or exists (select 1 from session_binding_attempts t where t.official_account_id=a.id and t.status in ('QUEUED','PREPARING','READY_FOR_LOGIN','DETECTING','AWAITING_CONFIRMATION')))" + suffix + " order by a.id limit 32";
            List<UUID> accounts = after == null ? jdbc.query(sql, (rs,n) -> rs.getObject("id", UUID.class))
                    : jdbc.query(sql, (rs,n) -> rs.getObject("id", UUID.class), after);
            if (accounts.isEmpty()) { after = null; return; }
            for (UUID account : accounts) {
                if (!inFlight.add(account)) continue;
                try {
                    workers.execute(() -> {
                        try { service.advance(account); }
                        // A logout fence, unavailable database or upstream failure
                        // never stops the timer or another account's progression.
                        catch (RuntimeException ignored) { /* A later bounded tick retries. No provider body is logged. */ }
                        finally { inFlight.remove(account); }
                    });
                } catch (RejectedExecutionException busy) { inFlight.remove(account); }
            }
            after = accounts.get(accounts.size() - 1);
        } catch (RuntimeException ignored) { /* Database startup/shutdown is retried without sensitive logging. */ }
    }

    @Override @PreDestroy public void close() {
        timer.shutdownNow();
        workers.shutdownNow();
    }
}
