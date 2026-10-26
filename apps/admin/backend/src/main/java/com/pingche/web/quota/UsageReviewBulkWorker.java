package com.pingche.web.quota;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class UsageReviewBulkWorker {
    private final UsageReviewBulkService jobs;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "usage-review-bulk"); thread.setDaemon(true); return thread;
    });
    public UsageReviewBulkWorker(UsageReviewBulkService jobs) { this.jobs = jobs; }
    @PostConstruct public void start() { worker.scheduleWithFixedDelay(this::tick, 5, 1, TimeUnit.SECONDS); }
    private void tick() {
        try { jobs.processNextBatch(); }
        catch (RuntimeException ignored) { /* Database outages leave the durable cursor for the next attempt. */ }
    }
    @PreDestroy public void close() { worker.shutdownNow(); }
}
