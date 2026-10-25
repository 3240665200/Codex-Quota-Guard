package com.pingche.web.officialsession;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public final class OfficialQuotaPollingScheduler {
    private final OfficialQuotaPollingService service;
    private final boolean enabled;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "official-quota-timer"); thread.setDaemon(true); return thread;
    });
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), task -> { Thread thread = new Thread(task, "official-quota-worker"); thread.setDaemon(true); return thread; });
    public OfficialQuotaPollingScheduler(OfficialQuotaPollingService service,
            @Value("${app.official-quota-scheduler-enabled:${OFFICIAL_QUOTA_SCHEDULER_ENABLED:true}}") boolean enabled) {
        this.service = service; this.enabled = enabled;
    }
    @PostConstruct public void start() { if (enabled) timer.scheduleWithFixedDelay(this::tick, 10, 10, TimeUnit.SECONDS); }
    private void tick() {
        try {
            for (UUID id : service.dueAccounts()) {
                if (!inFlight.add(id)) continue;
                try { workers.execute(() -> {
                    try { service.refreshWhenDue(id); }
                    catch (RuntimeException ignored) { /* Persisted leases/cooldowns survive a later retry. No provider body is logged. */ }
                    finally { inFlight.remove(id); }
                }); } catch (RejectedExecutionException ignored) { inFlight.remove(id); }
            }
        } catch (RuntimeException ignored) { /* Database startup/shutdown must not terminate the timer. */ }
    }
    @PreDestroy public void close() { timer.shutdownNow(); workers.shutdownNow(); }
}
