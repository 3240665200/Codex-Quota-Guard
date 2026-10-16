package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.SystemLogoutEvent;
import com.pingche.web.weblogin.BrowserInputPolicy;
import com.pingche.web.weblogin.ManagedLoginBrowser;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.context.event.EventListener;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Coordinates server browsers through the same durable account reservation as desktop login. */
@Service
public class WebLoginService implements AutoCloseable {
    private static final String ACTIVE = "('WAITING_FOR_HELPER','WAITING_FOR_LOGIN','SAVING')";
    private static final Set<String> ORIGINS = Set.of("https://chatgpt.com", "https://auth.openai.com", "https://auth0.openai.com");
    private final JdbcTemplate jdbc;
    private final OfficialConnectionService connections;
    private final OfficialSessionClient client;
    private final ManagedLoginBrowser browser;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final OfficialSessionLogoutFence logoutFence;
    private final ConcurrentHashMap<UUID, Entry> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean();
    private volatile ScheduledExecutorService cleaner;
    private volatile boolean closed;

    public WebLoginService(JdbcTemplate jdbc, OfficialConnectionService connections, OfficialSessionClient client,
            ManagedLoginBrowser browser, Clock clock, PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.connections=connections;this.client=client;this.browser=browser;this.clock=clock;
        transaction=new TransactionTemplate(manager);
        logoutFence=new OfficialSessionLogoutFence(jdbc);
    }

    public record Job(UUID id, UUID officialAccountId, String accountName, String state, Instant expiresAt,
            String errorCode, UUID proxyNodeId, String pageOrigin, boolean readyToSave, UUID frontProxyNodeId) {
        public Job(UUID id, UUID officialAccountId, String accountName, String state, Instant expiresAt,
                String errorCode, UUID proxyNodeId, String pageOrigin, boolean readyToSave) {
            this(id, officialAccountId, accountName, state, expiresAt, errorCode, proxyNodeId, pageOrigin, readyToSave, null);
        }
    }
    private record Row(UUID id, UUID owner, UUID account, String accountName, String state, Instant expiresAt,
            String errorCode, UUID proxyNode, long version, long ownerVersion) { }
    private static final class Entry {
        final UUID owner;
        final Instant expiresAt;
        final UUID frontProxyNodeId;
        volatile ManagedLoginBrowser.Session session;
        volatile boolean closed;
        Instant nextFrame = Instant.MIN, inputWindow = Instant.MIN;
        int inputs;
        String pageOrigin;
        boolean ready;
        Entry(UUID owner,Instant expiresAt,UUID frontProxyNodeId) {
            this.owner=owner;this.expiresAt=expiresAt;this.frontProxyNodeId=frontProxyNodeId;
        }
    }

    @PostConstruct
    public void initialize() {
        if (!initialized.compareAndSet(false,true)) return;
        // A restarted process cannot safely recreate an authenticated browser or its pending inputs.
        jdbc.update("update official_browser_login_jobs set state='FAILED',error_code='BROWSER_LOGIN_SERVER_RESTARTED' "
                + "where login_method='WEB' and state in " + ACTIVE);
        cleaner=Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread=new Thread(task,"official-web-login-cleanup");thread.setDaemon(true);return thread;
        });
        cleaner.scheduleWithFixedDelay(this::safeCleanup,5,5,TimeUnit.SECONDS);
    }

    public synchronized Job create(UUID owner, UUID account, BrowserLoginService.StartRequest request) {
        cleanup();
        if (closed || !browser.available()) throw failure("BROWSER_LOGIN_BROWSER_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE);
        Row row=tx(() -> {
            var names=jdbc.query("select label from official_accounts where id=? for update",(rs,n) -> rs.getString(1),account);
            if(names.isEmpty()) throw failure("OFFICIAL_ACCOUNT_NOT_FOUND",HttpStatus.NOT_FOUND);
            logoutFence.assertAvailable(account);
            long ownerVersion=ownerVersion(owner,true);
            var current=connections.read(account);
            if(!current.encryptionReady()) throw failure("OFFICIAL_ENCRYPTION_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE);
            if(request.version()==null || request.version()!=current.version()) throw failure("OFFICIAL_CONNECTION_VERSION_CONFLICT",HttpStatus.CONFLICT);
            if(request.proxyNodeId()==null)throw failure("OFFICIAL_PROXY_REQUIRED",HttpStatus.BAD_REQUEST);
            if(request.proxyNodeId().equals(request.frontProxyNodeId()))throw failure("PROXY_CHAIN_INVALID",HttpStatus.BAD_REQUEST);
            if(jdbc.queryForObject("select count(*) from managed_proxy_nodes where id=? and enabled=true",Integer.class,request.proxyNodeId())!=1)
                throw failure("OFFICIAL_PROXY_UNAVAILABLE",HttpStatus.BAD_REQUEST);
            if(request.frontProxyNodeId()!=null && jdbc.queryForObject("select count(*) from managed_proxy_nodes where id=? and enabled=true",Integer.class,request.frontProxyNodeId())!=1)
                throw failure("OFFICIAL_PROXY_UNAVAILABLE",HttpStatus.BAD_REQUEST);
            if(count("official_account_id=? and state in "+ACTIVE,account)>0
                    || count("login_method='WEB' and owner_id=? and state in "+ACTIVE,owner)>0)
                throw failure("BROWSER_LOGIN_BUSY",HttpStatus.CONFLICT);
            if(count("login_method='WEB' and state in "+ACTIVE)>=2) throw failure("BROWSER_LOGIN_CAPACITY",HttpStatus.TOO_MANY_REQUESTS);
            if(count("login_method='WEB' and owner_id=? and created_at>?",owner,at(clock.instant().minusSeconds(60)))>=5)
                throw failure("BROWSER_LOGIN_RATE_LIMITED",HttpStatus.TOO_MANY_REQUESTS);
            UUID id=UUID.randomUUID();
            jdbc.update("insert into official_browser_login_jobs(id,owner_id,official_account_id,account_name,proxy_node_id,connection_version,state,expires_at,created_at,login_method,owner_session_version) "
                    + "values(?,?,?,?,?,?,'WAITING_FOR_LOGIN',?,?,'WEB',?)",id,owner,account,names.get(0),request.proxyNodeId(),request.version(),
                    at(clock.instant().plusSeconds(600)),at(clock.instant()),ownerVersion);
            return row(owner,id,false);
        });
        Entry entry=new Entry(owner,row.expiresAt(),request.frontProxyNodeId());
        sessions.put(row.id(),entry);
        try {
            var opened=entry.frontProxyNodeId==null ? browser.open(row.id(),row.proxyNode())
                    : browser.open(row.id(),entry.frontProxyNodeId,row.proxyNode());
            synchronized(entry) {
                if(entry.closed) { opened.close();throw failure("BROWSER_LOGIN_NOT_ACTIVE",HttpStatus.CONFLICT); }
                entry.session=opened;
            }
            requireActive(owner,row.id(),"WAITING_FOR_LOGIN");
            return job(row);
        } catch(RuntimeException failure) {
            failSession(row.id(),"FAILED",safeCode(failure));
            throw failure(safeCode(failure),HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public Job get(UUID owner,UUID id) { cleanup();return job(row(owner,id,false)); }

    public ManagedLoginBrowser.Frame frame(UUID owner,UUID id) {
        requireActive(owner,id,"WAITING_FOR_LOGIN");
        Entry entry=requireSession(id);
        synchronized(entry) {
            if(clock.instant().isBefore(entry.nextFrame)) throw failure("BROWSER_LOGIN_RATE_LIMITED",HttpStatus.TOO_MANY_REQUESTS);
            entry.nextFrame=clock.instant().plusMillis(350);
        }
        try {
            var frame=entry.session.frame();
            requireActive(owner,id,"WAITING_FOR_LOGIN");
            if(frame==null || frame.width()!=ManagedLoginBrowser.WIDTH || frame.height()!=ManagedLoginBrowser.HEIGHT
                    || frame.jpegBase64()==null || frame.jpegBase64().isBlank() || frame.jpegBase64().length()>2_097_152)
                throw failure("BROWSER_LOGIN_FRAME_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE);
            boolean sessionChanged="WEB_LOGIN_SESSION_CHANGED".equals(frame.errorCode());
            if(frame.errorCode()!=null && !sessionChanged) throw failure(browserCode(frame.errorCode()),HttpStatus.SERVICE_UNAVAILABLE);
            String origin=safeOrigin(frame.pageOrigin());
            synchronized(entry) {entry.pageOrigin=origin;entry.ready=!sessionChanged && frame.readyToSave() && "https://chatgpt.com".equals(origin);}
            return new ManagedLoginBrowser.Frame(frame.width(),frame.height(),frame.jpegBase64(),origin,entry.ready,sessionChanged?"BROWSER_LOGIN_SESSION_CHANGED":null);
        } catch(RuntimeException failure) {
            String code=safeCode(failure);failSession(id,"FAILED",code);throw failure(code,HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public void input(UUID owner,UUID id,ManagedLoginBrowser.Input input) {
        requireActive(owner,id,"WAITING_FOR_LOGIN");
        try {BrowserInputPolicy.validate(input);} catch(IllegalArgumentException ignored) {throw failure("BROWSER_LOGIN_INPUT_INVALID",HttpStatus.BAD_REQUEST);}
        Entry entry=requireSession(id);
        synchronized(entry) {
            if(!entry.inputWindow.plusSeconds(1).isAfter(clock.instant())) {entry.inputWindow=clock.instant();entry.inputs=0;}
            if(++entry.inputs>50) throw failure("BROWSER_LOGIN_RATE_LIMITED",HttpStatus.TOO_MANY_REQUESTS);
        }
        try {entry.session.input(input);requireActive(owner,id,"WAITING_FOR_LOGIN");}
        catch(RuntimeException failure) {String code=safeCode(failure);failSession(id,"FAILED",code);throw failure(code,HttpStatus.SERVICE_UNAVAILABLE);}
    }

    public Job complete(UUID owner,UUID id) {
        requireActive(owner,id,"WAITING_FOR_LOGIN");
        Entry entry=requireSession(id);
        Row work=tx(() -> {
            Row current=row(owner,id,true);
            validateActive(current,"WAITING_FOR_LOGIN");
            jdbc.update("update official_browser_login_jobs set state='SAVING' where id=?",id);
            return current;
        });
        try {
            var captured=entry.session.capture();
            if(captured==null)throw failure("BROWSER_LOGIN_NOT_READY",HttpStatus.CONFLICT);
            var credentials=new OfficialSessionCredentials(captured.authorization(),captured.cookie(),captured.accountId());
            credentials.validate();
            // No stored authorization changes until this candidate has passed the official identity/session reads.
            var snapshot=entry.frontProxyNodeId==null ? client.fetch(credentials,work.proxyNode())
                    : client.fetch(credentials,entry.frontProxyNodeId,work.proxyNode());
            if(snapshot==null || snapshot.account()==null || snapshot.sessions()==null
                    || !credentials.requestedAccountId().equals(snapshot.account().accountId())
                    || snapshot.account().userId()==null || snapshot.account().userId().isBlank())
                throw failure("OFFICIAL_ACCOUNT_MISMATCH",HttpStatus.BAD_GATEWAY);
            tx(() -> {
                // Match binding's account-first order; NOWAIT on the owner also avoids user-deletion cycles.
                if(jdbc.query("select id from official_accounts where id=? for update",(rs,n) -> rs.getObject(1,UUID.class),work.account()).isEmpty())
                    throw failure("OFFICIAL_ACCOUNT_NOT_FOUND",HttpStatus.NOT_FOUND);
                logoutFence.assertAvailable(work.account());
                Row current=row(owner,id,true);
                validateActive(current,"SAVING");
                if(ownerVersion(owner,true)!=work.ownerVersion())throw failure("BROWSER_LOGIN_ACCESS_REVOKED",HttpStatus.FORBIDDEN);
                if(sessions.get(id)!=entry || entry.closed)throw failure("BROWSER_LOGIN_ACCESS_REVOKED",HttpStatus.CONFLICT);
                connections.saveVerified(work.account(),new OfficialConnectionRequest(credentials.authorization(),credentials.cookie(),
                        credentials.requestedAccountId(),work.proxyNode(),work.version(),entry.frontProxyNodeId),snapshot);
                jdbc.update("update official_browser_login_jobs set state='COMPLETED',error_code=null where id=?",id);
                return null;
            });
        } catch(RuntimeException failure) {
            String code=safeCode(failure);
            finish(id,"BROWSER_LOGIN_EXPIRED".equals(code)?"EXPIRED":"FAILED",code);
        } finally {closeSession(id);}
        return get(owner,id);
    }

    public Job cancel(UUID owner,UUID id) {
        tx(() -> {
            Row row=row(owner,id,true);
            if("SAVING".equals(row.state())) throw failure("BROWSER_LOGIN_SAVING",HttpStatus.CONFLICT);
            if(active(row.state())) jdbc.update("update official_browser_login_jobs set state='CANCELLED' where id=?",id);
            return null;
        });
        closeSession(id);
        return get(owner,id);
    }

    public void cancelOwner(UUID owner) {
        try{jdbc.update("update official_browser_login_jobs set state='CANCELLED',error_code='BROWSER_LOGIN_ACCESS_REVOKED' where owner_id=? and login_method='WEB' and state in "+ACTIVE,owner);}
        finally{sessions.forEach((id,entry) -> {if(entry.owner.equals(owner))closeSession(id);});}
    }

    @EventListener
    public void systemLogout(SystemLogoutEvent event) {cancelOwner(event.userId());}

    public void cleanup() {
        // Closing a locally expired process must also work while the database is temporarily unavailable.
        sessions.forEach((id,entry) -> {if(!entry.expiresAt.isAfter(clock.instant()))closeSession(id);});
        jdbc.update("update official_browser_login_jobs set state='EXPIRED',error_code='BROWSER_LOGIN_EXPIRED' where expires_at<=? and state in "+ACTIVE,at(clock.instant()));
        jdbc.update("update official_browser_login_jobs set state='CANCELLED',error_code='BROWSER_LOGIN_ACCESS_REVOKED' where login_method='WEB' and state in "+ACTIVE
                + " and not exists (select 1 from users u where u.id=official_browser_login_jobs.owner_id and u.role='ADMIN' and u.status='ACTIVE' and u.session_version=official_browser_login_jobs.owner_session_version)");
        sessions.forEach((id,entry) -> {
            if(count("id=? and login_method='WEB' and state in "+ACTIVE,id)==0)closeSession(id);
        });
    }

    private Row requireActive(UUID owner,UUID id,String state) {
        cleanup();
        Row current=row(owner,id,false);
        validateActive(current,state);
        return current;
    }
    private void validateActive(Row current,String state) {
        if(!current.expiresAt().isAfter(clock.instant())) throw failure("BROWSER_LOGIN_EXPIRED",HttpStatus.CONFLICT);
        if(!state.equals(current.state())) throw failure("BROWSER_LOGIN_NOT_ACTIVE",HttpStatus.CONFLICT);
        if(ownerVersion(current.owner())!=current.ownerVersion()) throw failure("BROWSER_LOGIN_ACCESS_REVOKED",HttpStatus.FORBIDDEN);
    }
    private long ownerVersion(UUID owner) {return ownerVersion(owner,false);}
    private long ownerVersion(UUID owner,boolean lock) {
        try {
            var rows=jdbc.query("select session_version from users where id=? and role='ADMIN' and status='ACTIVE'"+(lock?" for update nowait":""),(rs,n) -> rs.getLong(1),owner);
            if(rows.isEmpty()) throw failure("BROWSER_LOGIN_ACCESS_REVOKED",HttpStatus.FORBIDDEN);
            return rows.get(0);
        } catch(PessimisticLockingFailureException|QueryTimeoutException ignored) {
            throw failure("BROWSER_LOGIN_ACCESS_REVOKED",HttpStatus.CONFLICT);
        }
    }
    private Row row(UUID owner,UUID id,boolean lock) {
        var rows=jdbc.query("select * from official_browser_login_jobs where id=? and owner_id=? and login_method='WEB'"+(lock?" for update":""),(rs,n) -> map(rs),id,owner);
        if(rows.isEmpty()) throw failure("BROWSER_LOGIN_NOT_FOUND",HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
    private Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getObject("id",UUID.class),rs.getObject("owner_id",UUID.class),rs.getObject("official_account_id",UUID.class),rs.getString("account_name"),
                rs.getString("state"),rs.getObject("expires_at",OffsetDateTime.class).toInstant(),rs.getString("error_code"),rs.getObject("proxy_node_id",UUID.class),
                rs.getLong("connection_version"),rs.getLong("owner_session_version"));
    }
    private Job job(Row row) {
        Entry entry=sessions.get(row.id());
        String origin=null;boolean ready=false;
        if(entry!=null && active(row.state()))synchronized(entry){origin=entry.pageOrigin;ready=entry.ready;}
        return new Job(row.id(),row.account(),row.accountName(),row.state(),row.expiresAt(),row.errorCode(),row.proxyNode(),origin,ready,
                entry==null ? null : entry.frontProxyNodeId);
    }
    private Entry requireSession(UUID id) {
        Entry entry=sessions.get(id);
        if(entry==null || entry.closed || entry.session==null) throw failure("BROWSER_LOGIN_BROWSER_UNAVAILABLE",HttpStatus.SERVICE_UNAVAILABLE);
        return entry;
    }
    private void finish(UUID id,String state,String code) {
        jdbc.update("update official_browser_login_jobs set state=?,error_code=? where id=? and login_method='WEB' and state in "+ACTIVE,state,code,id);
    }
    private void failSession(UUID id,String state,String code) {try{finish(id,state,code);}finally{closeSession(id);}}
    private void closeSession(UUID id) {
        Entry entry=sessions.remove(id);
        if(entry!=null)synchronized(entry){entry.closed=true;if(entry.session!=null)try{entry.session.close();}catch(RuntimeException ignored){ }}
    }
    private String safeOrigin(String value) {
        if(value==null)return null;
        try {URI uri=URI.create(value);String origin=uri.getScheme()+"://"+uri.getHost();return ORIGINS.contains(origin)?origin:null;}
        catch(IllegalArgumentException ignored){return null;}
    }
    private String safeCode(RuntimeException failure) {
        if(failure instanceof DomainException domain && domain.getCode()!=null
                && domain.getCode().matches("(?:OFFICIAL|BROWSER_LOGIN)_[A-Z_]{1,50}"))return domain.getCode();
        if(failure instanceof DomainException domain)return browserCode(domain.getCode());
        return "BROWSER_LOGIN_BROWSER_UNAVAILABLE";
    }
    private String browserCode(String code) {
        return switch(code==null?"":code) {
            case "WEB_LOGIN_BROWSER_DISABLED" -> "BROWSER_LOGIN_BROWSER_DISABLED";
            case "WEB_LOGIN_BROWSER_NOT_FOUND" -> "BROWSER_LOGIN_BROWSER_NOT_FOUND";
            case "WEB_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED" -> "BROWSER_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED";
            case "WEB_LOGIN_DESTINATION_BLOCKED" -> "BROWSER_LOGIN_DESTINATION_BLOCKED";
            case "WEB_LOGIN_SESSION_CHANGED" -> "BROWSER_LOGIN_SESSION_CHANGED";
            default -> "BROWSER_LOGIN_BROWSER_UNAVAILABLE";
        };
    }
    private boolean active(String state) {return List.of("WAITING_FOR_HELPER","WAITING_FOR_LOGIN","SAVING").contains(state);}
    private int count(String condition,Object...args) {return jdbc.queryForObject("select count(*) from official_browser_login_jobs where "+condition,Integer.class,args);}
    private OffsetDateTime at(Instant instant) {return instant.atOffset(ZoneOffset.UTC);}
    private <T>T tx(Supplier<T> work) {return transaction.execute(ignored -> work.get());}
    private DomainException failure(String code,HttpStatus status) {return new DomainException(code,"Official browser login could not be completed",status);}
    private void safeCleanup() {try{cleanup();}catch(RuntimeException ignored){ /* A later sweep retries without exposing browser data. */ }}
    @Override @PreDestroy public void close() {
        closed=true;
        if(cleaner!=null)cleaner.shutdownNow();
        sessions.forEach((id,entry) -> {
            try{finish(id,"CANCELLED","BROWSER_LOGIN_SERVER_STOPPED");}catch(RuntimeException ignored){ }
            finally{closeSession(id);}
        });
    }
}
