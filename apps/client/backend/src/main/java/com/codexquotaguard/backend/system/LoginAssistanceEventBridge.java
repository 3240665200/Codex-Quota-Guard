package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.AssistanceEventStream;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Loopback notification bridge. Closing a subscription never cancels a server-owned mail task. */
@Service
public final class LoginAssistanceEventBridge implements AutoCloseable {
    private final SystemSessionService sessions;
    private final ControlPlaneGateway gateway;
    private final Supplier<SseEmitter> emitters;
    private final Clock clock;
    private final Set<Entry> active=ConcurrentHashMap.newKeySet();
    private final Semaphore capacity=new Semaphore(2);
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final ExecutorService readers=Executors.newFixedThreadPool(2,task->{var thread=new Thread(task,"assistance-event-reader");thread.setDaemon(true);return thread;});
    private final ScheduledExecutorService cleaner=Executors.newSingleThreadScheduledExecutor(task->{var thread=new Thread(task,"assistance-event-lifetime");thread.setDaemon(true);return thread;});

    @Autowired public LoginAssistanceEventBridge(SystemSessionService sessions,ControlPlaneGateway gateway) {
        this(sessions,gateway,()->new SseEmitter(0L),Clock.systemUTC());
    }
    LoginAssistanceEventBridge(SystemSessionService sessions,ControlPlaneGateway gateway,Supplier<SseEmitter> emitters,Clock clock) {
        this.sessions=sessions;this.gateway=gateway;this.emitters=emitters;this.clock=clock;
        cleaner.scheduleWithFixedDelay(this::sweep,1,1,TimeUnit.SECONDS);
    }

    public SseEmitter subscribe() {
        if(stopped.get() || !capacity.tryAcquire())throw new ControlPlaneUnavailableException();
        AtomicReference<AssistanceEventStream> opened=new AtomicReference<>();
        Entry entry=null;
        try {
            var identity=sessions.currentProbe();
            sessions.requireCurrent(identity);
            var upstream=sessions.withAuthenticatedToken(token->{var stream=gateway.loginAssistanceEvents(token);opened.set(stream);return stream;});
            sessions.requireCurrent(identity);
            if(upstream==null || stopped.get())throw new ControlPlaneUnavailableException();
            entry=new Entry(identity,upstream,emitters.get());
            active.add(entry);
            Entry owned=entry;
            owned.emitter.onCompletion(owned::close);
            owned.emitter.onTimeout(owned::close);
            owned.emitter.onError(ignored->owned.close());
            owned.task=readers.submit(owned::read);
            if(owned.closed.get())owned.task.cancel(true);
            return owned.emitter;
        } catch(RuntimeException failure) {
            if(entry!=null)entry.close();
            throw failure;
        } finally {
            if(entry==null) {
                var upstream=opened.get();if(upstream!=null)upstream.close();
                capacity.release();
            }
        }
    }

    int activeCount(){return active.size();}
    void sweep() {
        Instant now=clock.instant();
        for(Entry entry:active) {
            if(!sessions.matches(entry.identity) || !entry.lastActivity.plusSeconds(45).isAfter(now)
                    || !entry.created.plusSeconds(600).isAfter(now))entry.close();
        }
    }

    private final class Entry {
        final SystemSessionService.Probe identity;
        final AssistanceEventStream upstream;
        final SseEmitter emitter;
        final Instant created=clock.instant();
        final AtomicBoolean closed=new AtomicBoolean();
        volatile Instant lastActivity=created;
        volatile Future<?> task;
        Entry(SystemSessionService.Probe identity,AssistanceEventStream upstream,SseEmitter emitter){this.identity=identity;this.upstream=upstream;this.emitter=emitter;}
        void read() {
            try {
                while(!closed.get() && sessions.matches(identity)) {
                    var signal=upstream.next();
                    if(signal==null || closed.get() || !sessions.matches(identity))break;
                    lastActivity=clock.instant();
                    // Never send an upstream body or token; only these fixed invalidations cross the boundary.
                    emitter.send(SseEmitter.event().name(signal.eventName()).data("{}"));
                }
            } catch(IOException|RuntimeException ignored) { /* No upstream content in errors or logs. */ }
            finally {close();}
        }
        void close() {
            if(!closed.compareAndSet(false,true))return;
            upstream.close();
            var running=task;if(running!=null)running.cancel(true);
            active.remove(this);capacity.release();
            try{emitter.complete();}catch(RuntimeException ignored){ }
        }
    }

    @Override @PreDestroy public void close() {
        if(stopped.compareAndSet(false,true)){active.forEach(Entry::close);cleaner.shutdownNow();readers.shutdownNow();}
    }
}
