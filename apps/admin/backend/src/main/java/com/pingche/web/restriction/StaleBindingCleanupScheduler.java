package com.pingche.web.restriction;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class StaleBindingCleanupScheduler {
    private final StaleBindingCleanupService service;
    private final boolean enabled;
    private final ScheduledExecutorService executor=Executors.newSingleThreadScheduledExecutor(task -> {
        var thread=new Thread(task,"stale-binding-cleanup"); thread.setDaemon(true); return thread;
    });
    public StaleBindingCleanupScheduler(StaleBindingCleanupService service,
            @Value("${app.restriction-scheduler-enabled:${RESTRICTION_SCHEDULER_ENABLED:true}}") boolean enabled) {
        this.service=service; this.enabled=enabled;
    }
    @PostConstruct public void start() { if(enabled) executor.scheduleWithFixedDelay(this::tick,10,10,TimeUnit.SECONDS); }
    private void tick() {
        try { for(var account:service.dueAccounts()) {
            try { service.checkAccount(account); } catch(RuntimeException ignored) { /* Other accounts still run. */ }
        } } catch(RuntimeException ignored) { /* A database outage must not stop subsequent checks. */ }
    }
    @PreDestroy public void close() { executor.shutdownNow(); }
}
