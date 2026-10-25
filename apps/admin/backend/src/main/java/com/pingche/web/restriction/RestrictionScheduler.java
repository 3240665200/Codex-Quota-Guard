package com.pingche.web.restriction;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The database lease, not the local in-flight set, is authoritative across server instances. */
@Component
public class RestrictionScheduler {
    private final RestrictionManagementService service;
    private final boolean enabled;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> { var thread = new Thread(task,"restriction-timer"); thread.setDaemon(true); return thread; });
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(32),task -> { var thread = new Thread(task,"restriction-worker"); thread.setDaemon(true); return thread; });
    public RestrictionScheduler(RestrictionManagementService service,
            @Value("${app.restriction-scheduler-enabled:${RESTRICTION_SCHEDULER_ENABLED:true}}") boolean enabled) { this.service = service; this.enabled = enabled; }
    @PostConstruct public void start() { if (enabled) timer.scheduleWithFixedDelay(this::tick,10,10,TimeUnit.SECONDS); }
    private void tick() {
        try { for (UUID account : service.dueAccounts()) {
            if (!inFlight.add(account)) continue;
            try { workers.execute(() -> { try { service.checkAccount(account); } catch (RuntimeException ignored) { /* Durable leases and official write fences survive this worker. */ } finally { inFlight.remove(account); } }); }
            catch (RejectedExecutionException busy) { inFlight.remove(account); }
        } } catch (RuntimeException ignored) { /* Startup/shutdown and a failed account never terminate the timer. */ }
    }
    @PreDestroy public void close() { timer.shutdownNow(); workers.shutdownNow(); }
}
