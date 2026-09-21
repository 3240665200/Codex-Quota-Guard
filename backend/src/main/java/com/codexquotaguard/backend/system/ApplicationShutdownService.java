package com.codexquotaguard.backend.system;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import java.util.concurrent.Executor;

@Service
public final class ApplicationShutdownService {

    private static final long RESPONSE_GRACE_MILLIS = 150L;

    private final ConfigurableApplicationContext context;
    private final Executor shutdownExecutor;
    private final long delayMillis;

    @Autowired
    public ApplicationShutdownService(ConfigurableApplicationContext context) {
        this(context, ApplicationShutdownService::startShutdownThread, RESPONSE_GRACE_MILLIS);
    }

    ApplicationShutdownService(ConfigurableApplicationContext context, Executor shutdownExecutor, long delayMillis) {
        this.context = context;
        this.shutdownExecutor = shutdownExecutor;
        this.delayMillis = delayMillis;
    }

    public void requestShutdown() {
        shutdownExecutor.execute(() -> {
            sleepBeforeShutdown();
            context.close();
        });
    }

    private void sleepBeforeShutdown() {
        if (delayMillis <= 0L) {
            return;
        }
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void startShutdownThread(Runnable action) {
        var thread = new Thread(action, "quota-guard-shutdown");
        thread.setDaemon(false);
        thread.start();
    }
}
