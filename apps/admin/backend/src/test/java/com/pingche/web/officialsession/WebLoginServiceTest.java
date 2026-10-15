package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.weblogin.ManagedLoginBrowser;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class WebLoginServiceTest {
    JdbcTemplate jdbc;
    SingleConnectionDataSource source;
    OfficialConnectionService connections;
    OfficialSessionClient client;
    ManagedLoginBrowser browser;
    ManagedLoginBrowser.Session session;
    WebLoginService service;
    BrowserLoginService desktop;
    UUID owner = UUID.randomUUID(), account = UUID.randomUUID();
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));
    Clock clock;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:web-login-"+UUID.randomUUID()+";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table users(id uuid primary key,role varchar(16),status varchar(16),session_version bigint not null)");
        jdbc.execute("create table official_accounts(id uuid primary key,label varchar(120))");
        jdbc.execute("create table managed_proxy_nodes(id uuid primary key,enabled boolean not null default true)");
        jdbc.execute("create table official_session_logout_plans(official_account_id uuid,status varchar(32))");
        jdbc.update("insert into users values (?,'ADMIN','ACTIVE',0)",owner);
        jdbc.update("insert into official_accounts values (?,?)",account,"Synthetic account");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V25__add_official_browser_login_jobs.sql"),
                new ClassPathResource("db/migration/V28__add_server_web_login_jobs.sql")).execute(source);
        connections=mock(OfficialConnectionService.class);
        client=mock(OfficialSessionClient.class);
        browser=mock(ManagedLoginBrowser.class);
        session=mock(ManagedLoginBrowser.Session.class);
        when(browser.available()).thenReturn(true);
        when(browser.open(any(),any())).thenReturn(session);
        when(connections.read(any())).thenReturn(new OfficialConnectionResponse(false,true,"NOT_CONFIGURED",null,null,false,false,null,null,null,null,0));
        when(session.frame()).thenReturn(new ManagedLoginBrowser.Frame(1024,768,"c3ludGhldGlj","https://chatgpt.com",true,null));
        when(session.capture()).thenReturn(new ManagedLoginBrowser.Credentials("__Secure-next-auth.session-token=synthetic-cookie","Bearer synthetic-token","synthetic-account"));
        when(client.fetch(any(),any())).thenReturn(snapshot());
        clock=mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        var manager=new DataSourceTransactionManager(source);
        service=new WebLoginService(jdbc,connections,client,browser,clock,manager);
        desktop=new BrowserLoginService(jdbc,connections,clock,manager);
    }

    @AfterEach void close() { if(service!=null)service.close();source.destroy(); }

    @Test void serverJobsNeverEnterTheDesktopHelperChannel() {
        var job=create();
        assertThat(job.state()).isEqualTo("WAITING_FOR_LOGIN");
        assertThat(jdbc.queryForObject("select login_method from official_browser_login_jobs where id=?",String.class,job.id())).isEqualTo("WEB");
        assertThat(desktop.list(owner)).isEmpty();
        assertThatThrownBy(() -> desktop.get(owner,job.id())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> desktop.claim(owner,job.id(),UUID.randomUUID())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> desktop.cancel(owner,job.id())).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> desktop.complete(owner,job.id(),new BrowserLoginService.CompleteRequest(UUID.randomUUID(),"Bearer injected",null,"synthetic-account")))
                .isInstanceOf(DomainException.class);
        assertThat(jdbc.queryForMap("select * from official_browser_login_jobs where id=?",job.id()).toString())
                .doesNotContain("synthetic-token","synthetic-cookie");
        verify(connections,never()).save(any(),any());
    }

    @Test void failedCandidateVerificationPreservesTheOldAuthorization() {
        var job=create();
        when(client.fetch(any(),any())).thenThrow(new DomainException("OFFICIAL_AUTH_REQUIRED","private-secret",HttpStatus.BAD_GATEWAY));
        var result=service.complete(owner,job.id());
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("OFFICIAL_AUTH_REQUIRED");
        verify(connections,never()).saveVerified(any(),any(),any());
        verify(connections,never()).save(any(),any());
        verify(session).close();
        assertThat(result.toString()).doesNotContain("private-secret","synthetic-token","synthetic-cookie");
    }

    @Test void successfulCandidateIsVerifiedOnceBeforeAtomicSaveAndCannotBeReplayed() {
        var job=create();
        var result=service.complete(owner,job.id());
        assertThat(result.state()).isEqualTo("COMPLETED");
        var ordered=inOrder(client,connections);
        ordered.verify(client).fetch(any(OfficialSessionCredentials.class),isNull());
        ordered.verify(connections).saveVerified(eq(account),argThat(request -> request.version()==0L && request.authorization().equals("Bearer synthetic-token")),any());
        verify(connections,never()).refresh(any());
        verify(session).close();
        assertThatThrownBy(() -> service.complete(owner,job.id())).isInstanceOf(DomainException.class);
        verify(client,times(1)).fetch(any(),any());
    }

    @Test void changedIdentityAndExpiredVerificationNeverSave() {
        var job=create();
        when(client.fetch(any(),any())).thenAnswer(call -> { now.set(now.get().plusSeconds(601));return snapshot(); });
        assertThat(service.complete(owner,job.id()).state()).isEqualTo("EXPIRED");
        verify(connections,never()).saveVerified(any(),any(),any());
        verify(session).close();
    }

    @Test void everyFrameAndInputChecksOwnerStatusSessionVersionAndDeadline() {
        var job=create();
        assertThatThrownBy(() -> service.frame(UUID.randomUUID(),job.id())).isInstanceOf(DomainException.class);
        assertThat(service.frame(owner,job.id()).pageOrigin()).isEqualTo("https://chatgpt.com");
        jdbc.update("update users set session_version=1 where id=?",owner);
        assertThatThrownBy(() -> service.input(owner,job.id(),new ManagedLoginBrowser.Input("TEXT",null,null,null,null,"synthetic",false,false)))
                .isInstanceOf(DomainException.class);
        verify(session,never()).input(any());
        verify(session).close();
        assertThat(service.get(owner,job.id()).state()).isEqualTo("CANCELLED");
    }

    @Test void cleanupExpiresAndCancelsDisabledOwnersWithoutReopeningBrowsers() {
        var first=create();
        now.set(now.get().plusSeconds(601));
        service.cleanup();
        assertThat(service.get(owner,first.id()).state()).isEqualTo("EXPIRED");
        verify(session).close();
        var second=create();
        jdbc.update("update users set status='DISABLED' where id=?",owner);
        service.cleanup();
        assertThat(service.get(owner,second.id()).state()).isEqualTo("CANCELLED");
        verify(browser,times(2)).open(any(),any());
    }

    @Test void oneOwnerAndTwoGlobalJobsAreBoundedAndLogoutClosesOnlyThatOwner() {
        var first=create();
        UUID secondAccount=UUID.randomUUID();
        jdbc.update("insert into official_accounts values (?,?)",secondAccount,"Second account");
        assertThatThrownBy(() -> service.create(owner,secondAccount,new BrowserLoginService.StartRequest(0L,null))).isInstanceOf(DomainException.class);
        UUID secondOwner=UUID.randomUUID();
        jdbc.update("insert into users values (?,'ADMIN','ACTIVE',0)",secondOwner);
        service.create(secondOwner,secondAccount,new BrowserLoginService.StartRequest(0L,null));
        UUID thirdOwner=UUID.randomUUID(),thirdAccount=UUID.randomUUID();
        jdbc.update("insert into users values (?,'ADMIN','ACTIVE',0)",thirdOwner);
        jdbc.update("insert into official_accounts values (?,?)",thirdAccount,"Third account");
        assertThatThrownBy(() -> service.create(thirdOwner,thirdAccount,new BrowserLoginService.StartRequest(0L,null))).isInstanceOf(DomainException.class);
        service.cancelOwner(owner);
        assertThat(service.get(owner,first.id()).state()).isEqualTo("CANCELLED");
        verify(browser,times(2)).open(any(),any());
    }

    @Test void restartFailsUnrestorableWebTasksButLeavesDesktopJobs() {
        var web=create();
        service.close();
        var desktopJob=desktop.create(owner,account,new BrowserLoginService.StartRequest(0L,null));
        jdbc.update("update official_browser_login_jobs set state='WAITING_FOR_LOGIN' where id=?",web.id());
        var restarted=new WebLoginService(jdbc,connections,client,browser,clock,new DataSourceTransactionManager(source));
        try {
            restarted.initialize();
            assertThat(restarted.get(owner,web.id()).state()).isEqualTo("FAILED");
            assertThat(desktop.get(owner,desktopJob.id()).state()).isEqualTo("WAITING_FOR_HELPER");
        } finally { restarted.close(); }
        verify(browser,times(1)).open(any(),any());
    }

    @Test void officialLogoutFenceAndRuntimeAvailabilityFailBeforeOpening() {
        jdbc.update("insert into official_session_logout_plans values (?,'UNKNOWN')",account);
        assertThatThrownBy(this::create).isInstanceOf(DomainException.class);
        jdbc.update("delete from official_session_logout_plans");
        when(browser.available()).thenReturn(false);
        assertThatThrownBy(this::create).isInstanceOf(DomainException.class);
        verify(browser,never()).open(any(),any());
    }

    @Test void invalidInputAndFrameRateLimitsDoNotReachTheBrowser() {
        var job=create();
        assertThatThrownBy(() -> service.input(owner,job.id(),new ManagedLoginBrowser.Input("TEXT",null,null,null,null,"x".repeat(2049),false,false)))
                .isInstanceOf(DomainException.class);
        verify(session,never()).input(any());
        service.frame(owner,job.id());
        assertThatThrownBy(() -> service.frame(owner,job.id())).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(session,times(1)).frame();
    }

    @Test void changedSessionCanBeRecapturedWithoutReopeningTheBrowser() {
        var job=create();
        when(session.frame()).thenReturn(new ManagedLoginBrowser.Frame(1024,768,"c3ludGhldGlj","https://chatgpt.com",false,"WEB_LOGIN_SESSION_CHANGED"));
        var frame=service.frame(owner,job.id());
        assertThat(frame.errorCode()).isEqualTo("BROWSER_LOGIN_SESSION_CHANGED");
        assertThat(frame.readyToSave()).isFalse();
        assertThat(service.get(owner,job.id()).state()).isEqualTo("WAITING_FOR_LOGIN");
        service.input(owner,job.id(),new ManagedLoginBrowser.Input("OPEN_SESSIONS",null,null,null,null,null,false,false));
        verify(session).input(any());
        verify(session,never()).close();
    }

    @Test void officialLogoutOrSystemLogoutDuringCandidateValidationPreventsSave() {
        var job=create();
        when(client.fetch(any(),any())).thenAnswer(call -> {jdbc.update("insert into official_session_logout_plans values (?,'UNKNOWN')",account);return snapshot();});
        assertThat(service.complete(owner,job.id()).errorCode()).isEqualTo("OFFICIAL_SESSION_LOGOUT_BUSY");
        verify(connections,never()).saveVerified(any(),any(),any());
        jdbc.update("delete from official_session_logout_plans");
        var next=create();
        when(client.fetch(any(),any())).thenAnswer(call -> {service.cancelOwner(owner);return snapshot();});
        assertThat(service.complete(owner,next.id()).state()).isEqualTo("CANCELLED");
        verify(connections,never()).saveVerified(any(),any(),any());
    }

    @Test void boundedFramesUpToTwoMiBAreAcceptedAndBrowserFailuresHaveSafeCodes() {
        var job=create();
        when(session.frame()).thenReturn(new ManagedLoginBrowser.Frame(1024,768,"a".repeat(1_100_000),"https://chatgpt.com/?private=query",true,null));
        assertThat(service.frame(owner,job.id()).pageOrigin()).isEqualTo("https://chatgpt.com");
        now.set(now.get().plusSeconds(1));
        when(session.frame()).thenReturn(new ManagedLoginBrowser.Frame(1024,768,"c3ludGhldGlj",null,false,"WEB_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED"));
        assertThatThrownBy(() -> service.frame(owner,job.id())).isInstanceOfSatisfying(DomainException.class,
                failure -> assertThat(failure.getCode()).isEqualTo("BROWSER_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED"));
        assertThat(service.get(owner,job.id()).state()).isEqualTo("FAILED");
        verify(session).close();
    }

    @Test void localExpiryClosesBrowserEvenWhenTheDatabaseIsUnavailable() {
        create();
        now.set(now.get().plusSeconds(601));
        jdbc.execute("drop table official_browser_login_jobs");
        assertThatThrownBy(service::cleanup).isInstanceOf(RuntimeException.class);
        verify(session).close();
    }

    private WebLoginService.Job create() { return service.create(owner,account,new BrowserLoginService.StartRequest(0L,null)); }
    private OfficialSessionSnapshot snapshot() {
        return new OfficialSessionSnapshot(new OfficialAccountInfo("synthetic-user","owner@example.test","Owner","synthetic-account","Personal","plus","owner",false,true),
                new OfficialSessions(List.of(),true));
    }
}
