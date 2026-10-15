package com.codexquotaguard.backend.officiallogin;

import com.codexquotaguard.backend.controlplane.SystemUser;
import com.codexquotaguard.backend.system.SystemSessionService;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** One in-memory login task. Neither browser credentials nor system tokens reach the renderer. */
@Service
public final class OfficialLoginService {
    private static final Set<String> ACTIVE=Set.of("STARTING","WAITING_FOR_LOGIN","SAVING");
    private final SystemSessionService sessions;
    private final OfficialLoginGateway gateway;
    private final OfficialLoginBrowser browser;
    private final Clock clock;
    private final Executor executor;
    private final Object lock=new Object();
    private Work active;
    private boolean closed;

    @Autowired
    OfficialLoginService(SystemSessionService sessions,OfficialLoginGateway gateway,OfficialLoginBrowser browser,Clock clock) {
        this(sessions,gateway,browser,clock,Executors.newSingleThreadExecutor(action -> {
            Thread thread=new Thread(action,"official-login-helper");thread.setDaemon(true);return thread;
        }));
    }
    OfficialLoginService(SystemSessionService sessions,OfficialLoginGateway gateway,OfficialLoginBrowser browser,Clock clock,Executor executor) {
        this.sessions=sessions;this.gateway=gateway;this.browser=browser;this.clock=clock;this.executor=executor;
    }

    public record Status(UUID jobId,String state,String errorCode,String accountName) { }

    public List<OfficialLoginGateway.Job> jobs() {
        UUID owner=localAdmin().id();
        List<OfficialLoginGateway.Job> result=asOwner(owner,gateway::jobs);
        if(result==null || result.size()>30 || result.stream().anyMatch(job -> !validJob(job,null))) throw failure("OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE");
        return result;
    }

    public Status start(UUID jobId,OfficialLoginBrowser.Browser choice) {
        if(jobId==null || choice==null) throw failure("OFFICIAL_LOGIN_INVALID_PARAMETERS");
        UUID owner=localAdmin().id();
        Work work=new Work(jobId,owner,clock.instant().plusSeconds(600));
        synchronized(lock) {
            if(closed) throw failure("OFFICIAL_LOGIN_NOT_ACTIVE");
            if(active!=null && ACTIVE.contains(active.state)) throw failure("OFFICIAL_LOGIN_BUSY");
            active=work;
        }
        executor.execute(() -> prepare(work,choice));
        return statusFor(work);
    }

    private void prepare(Work work,OfficialLoginBrowser.Browser choice) {
        try {
            synchronized(lock) { if(!current(work)) return;work.worker=Thread.currentThread(); }
            if(!admin().id().equals(work.owner)) throw failure("OFFICIAL_LOGIN_IDENTITY_CHANGED");
            ensureCurrent(work);
            OfficialLoginGateway.Job claimed=asOwner(work.owner,token -> gateway.claim(token,work.id,work.helper));
            if(!validJob(claimed,work.id) || !"WAITING_FOR_LOGIN".equals(claimed.state())) throw failure("OFFICIAL_LOGIN_NOT_ACTIVE");
            if(!claimed.expiresAt().isAfter(clock.instant())) throw failure("OFFICIAL_LOGIN_TIMEOUT");
            synchronized(lock) {
                if(!current(work)) return;
                work.accountName=claimed.accountName();
                if(claimed.expiresAt().isBefore(work.expires)) work.expires=claimed.expiresAt();
            }
            launch(work,choice);
        } catch(RuntimeException failure) {
            finish(work,"FAILED",safeFailure(failure));
            cancelCentralQuietly(work);
        } finally { synchronized(lock) { work.worker=null; } }
    }

    public Status status() {
        UUID owner=localAdmin().id();
        Work previous;
        synchronized(lock) { previous=active; }
        if(previous!=null && !previous.owner.equals(owner)) {
            finish(previous,"FAILED","OFFICIAL_LOGIN_IDENTITY_CHANGED");
            return new Status(null,"IDLE",null,null);
        }
        poll();
        synchronized(lock) { return active==null ? new Status(null,"IDLE",null,null) : statusFor(active); }
    }

    public Status cancel() {
        UUID owner=localAdmin().id();
        Work work;
        OfficialLoginBrowser.Session owned;
        synchronized(lock) {
            work=active;
            if(work==null) return new Status(null,"IDLE",null,null);
            if(!owner.equals(work.owner)) throw failure("OFFICIAL_LOGIN_IDENTITY_CHANGED");
            if("SAVING".equals(work.state)) throw failure("OFFICIAL_LOGIN_SAVING");
            if(!ACTIVE.contains(work.state)) return statusFor(work);
            work.state="CANCELLED";work.error=null;owned=work.browser;work.browser=null;
            if(work.worker!=null && work.worker!=Thread.currentThread()) work.worker.interrupt();
        }
        if(owned!=null) owned.close();
        executor.execute(() -> {
            try { asOwner(owner,token -> gateway.cancel(token,work.id)); }
            catch(RuntimeException ignored) { synchronized(lock) { if(active==work) work.error="OFFICIAL_LOGIN_CANCEL_UNCONFIRMED"; } }
        });
        return statusFor(work);
    }

    @Scheduled(fixedDelay=1000)
    void poll() {
        Work work;
        synchronized(lock) { work=active;if(work==null || !current(work)) return; }
        if(!sameOwner(work.owner)) { finish(work,"FAILED","OFFICIAL_LOGIN_IDENTITY_CHANGED");return; }
        if(!work.expires.isAfter(clock.instant())) {
            finish(work,"FAILED","OFFICIAL_LOGIN_TIMEOUT");
            executor.execute(() -> cancelCentralQuietly(work));return;
        }
        synchronized(lock) {
            if(!current(work) || "SAVING".equals(work.state) || work.checking
                    || work.lastCheck.plusSeconds(3).isAfter(clock.instant())) return;
            work.checking=true;work.lastCheck=clock.instant();
        }
        executor.execute(() -> checkCentral(work));
    }

    private void checkCentral(Work work) {
        try {
            if(!isCurrent(work)) return;
            OfficialLoginGateway.Job job=asOwner(work.owner,token -> gateway.get(token,work.id));
            if(!validJob(job,work.id)) throw failure("OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE");
            if(!isCurrent(work)) return;
            if(!job.expiresAt().isAfter(clock.instant()) || "EXPIRED".equals(job.state())) {
                finish(work,"FAILED","OFFICIAL_LOGIN_TIMEOUT");return;
            }
            switch(job.state()) {
                case "CANCELLED" -> finish(work,"CANCELLED",null);
                case "FAILED" -> finish(work,"FAILED",OfficialLoginException.safeCode(job.errorCode()));
                case "COMPLETED" -> finish(work,"COMPLETED",null);
                case "WAITING_FOR_LOGIN","WAITING_FOR_HELPER" -> { }
                default -> finish(work,"FAILED","OFFICIAL_LOGIN_NOT_ACTIVE");
            }
        } catch(RuntimeException failure) {
            finish(work,"FAILED",safeFailure(failure));
            cancelCentralQuietly(work);
        } finally { synchronized(lock) { work.checking=false; } }
    }

    private void launch(Work work,OfficialLoginBrowser.Browser choice) {
        OfficialLoginBrowser.Session opened=null;
        try {
            ensureCurrent(work);opened=browser.open(choice);
            synchronized(lock) {
                if(!current(work) || !sameOwner(work.owner)) { opened.close();return; }
                work.browser=opened;work.state="WAITING_FOR_LOGIN";
            }
            opened.authorization().whenComplete((authorization,error) -> {
                if(!isCurrent(work)) return;
                executor.execute(() -> {
                    if(error!=null) { finish(work,"FAILED","OFFICIAL_LOGIN_BROWSER_UNAVAILABLE");cancelCentralQuietly(work); }
                    else submit(work,authorization);
                });
            });
        } catch(RuntimeException failure) {
            if(opened!=null && work.browser!=opened) opened.close();
            finish(work,"FAILED",safeFailure(failure));cancelCentralQuietly(work);
        }
    }

    private void submit(Work work,OfficialLoginBrowser.Authorization authorization) {
        try {
            ensureCurrent(work);
            if(authorization==null) throw failure("OFFICIAL_LOGIN_BROWSER_UNAVAILABLE");
            SystemUser user=admin();if(!user.id().equals(work.owner)) throw failure("OFFICIAL_LOGIN_IDENTITY_CHANGED");
            synchronized(lock) { ensureCurrent(work);work.state="SAVING";work.expires=clock.instant().plusSeconds(120); }
            OfficialLoginGateway.Job result=asOwner(work.owner,token -> gateway.complete(token,work.id,
                    new OfficialLoginGateway.Completion(work.helper,authorization.authorization(),authorization.cookie(),authorization.requestedAccountId())));
            if(!validJob(result,work.id)) throw failure("OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE");
            if("COMPLETED".equals(result.state())) finish(work,"COMPLETED",null);
            else if("CANCELLED".equals(result.state())) finish(work,"CANCELLED",null);
            else finish(work,"FAILED","EXPIRED".equals(result.state()) ? "OFFICIAL_LOGIN_TIMEOUT" : OfficialLoginException.safeCode(result.errorCode()));
        } catch(RuntimeException failure) { finish(work,"FAILED",safeFailure(failure));cancelCentralQuietly(work); }
    }

    private SystemUser admin() {
        SystemUser user=sessions.current();
        if(user==null || !"ADMIN".equals(user.role()) || !"ACTIVE".equals(user.status())) throw failure("OFFICIAL_LOGIN_ADMIN_REQUIRED");
        return user;
    }
    private SystemUser localAdmin() {
        SystemUser user=sessions.localUser().orElseThrow(() -> failure("OFFICIAL_LOGIN_SESSION_REQUIRED"));
        if(!"ADMIN".equals(user.role()) || !"ACTIVE".equals(user.status())) throw failure("OFFICIAL_LOGIN_ADMIN_REQUIRED");
        return user;
    }
    private <T> T asOwner(UUID owner,Function<String,T> operation) {
        return sessions.withAuthenticatedToken(token -> {
            if(!sameOwner(owner)) throw failure("OFFICIAL_LOGIN_IDENTITY_CHANGED");
            return operation.apply(token);
        });
    }
    private boolean sameOwner(UUID owner) { return sessions.localUserId().filter(owner::equals).isPresent(); }
    private boolean current(Work work) { return !closed && active==work && ACTIVE.contains(work.state); }
    private boolean isCurrent(Work work) { synchronized(lock) { return current(work); } }
    private void ensureCurrent(Work work) {
        synchronized(lock) { if(!current(work)) throw failure("OFFICIAL_LOGIN_NOT_ACTIVE"); }
        if(!sameOwner(work.owner)) throw failure("OFFICIAL_LOGIN_IDENTITY_CHANGED");
        if(!work.expires.isAfter(clock.instant())) throw failure("OFFICIAL_LOGIN_TIMEOUT");
    }
    private void finish(Work work,String state,String error) {
        OfficialLoginBrowser.Session owned;
        synchronized(lock) {
            if(active!=work || !ACTIVE.contains(work.state)) return;
            work.state=state;work.error=error;owned=work.browser;work.browser=null;
            if(work.worker!=null && work.worker!=Thread.currentThread()) work.worker.interrupt();
        }
        if(owned!=null) owned.close();
    }
    private Status statusFor(Work work) { synchronized(lock) { return new Status(work.id,work.state,work.error,work.accountName); } }
    private void cancelCentralQuietly(Work work) {
        if(!sameOwner(work.owner)) return;
        try { asOwner(work.owner,token -> gateway.cancel(token,work.id)); } catch(RuntimeException ignored) { }
    }
    private static boolean validJob(OfficialLoginGateway.Job job,UUID expected) {
        return job!=null && job.id()!=null && (expected==null || expected.equals(job.id())) && job.officialAccountId()!=null
                && job.accountName()!=null && job.accountName().length()<=120 && job.expiresAt()!=null
                && Set.of("WAITING_FOR_HELPER","WAITING_FOR_LOGIN","SAVING","COMPLETED","FAILED","CANCELLED","EXPIRED").contains(job.state());
    }
    private static String safeFailure(RuntimeException failure) {
        return failure instanceof OfficialLoginException safe ? safe.code() : "OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE";
    }
    private static OfficialLoginException failure(String code) { return new OfficialLoginException(code); }

    @PreDestroy
    public void close() {
        Work work;
        synchronized(lock) { work=active; }
        if(work!=null) finish(work,"CANCELLED",null);
        synchronized(lock) { closed=true; }
        if(work!=null) cancelCentralQuietly(work);
        if(executor instanceof ExecutorService service) service.shutdownNow();
    }

    private static final class Work {
        final UUID id,owner,helper=UUID.randomUUID();
        volatile Instant expires;
        Instant lastCheck=Instant.EPOCH;
        String state="STARTING",error,accountName;
        boolean checking;
        OfficialLoginBrowser.Session browser;
        Thread worker;
        Work(UUID id,UUID owner,Instant expires) { this.id=id;this.owner=owner;this.expires=expires; }
    }
}
