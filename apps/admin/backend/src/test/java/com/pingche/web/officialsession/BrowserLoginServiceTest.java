package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

class BrowserLoginServiceTest {
    JdbcTemplate jdbc;
    OfficialConnectionService connections;
    BrowserLoginService service;
    UUID owner = UUID.randomUUID(), account = UUID.randomUUID(), helper = UUID.randomUUID();
    AtomicReference<Instant> time = new AtomicReference<>(Instant.parse("2026-10-15T00:00:00Z"));
    SingleConnectionDataSource source;

    @BeforeEach void setup() {
        source = new SingleConnectionDataSource("jdbc:h2:mem:browser-login-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "", true);
        jdbc = new JdbcTemplate(source);
        jdbc.execute("create table users(id uuid primary key)");
        jdbc.execute("create table official_accounts(id uuid primary key, label varchar(120))");
        jdbc.execute("create table managed_proxy_nodes(id uuid primary key, enabled boolean not null default true)");
        jdbc.execute("create table official_session_logout_plans(official_account_id uuid,status varchar(32))");
        jdbc.update("insert into users values (?)", owner);
        jdbc.update("insert into official_accounts values (?,?)", account, "Synthetic account");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V25__add_official_browser_login_jobs.sql"),
                new ClassPathResource("db/migration/V28__add_server_web_login_jobs.sql")).execute(source);
        connections = mock(OfficialConnectionService.class);
        when(connections.read(account)).thenReturn(new OfficialConnectionResponse(false, true, "NOT_CONFIGURED", null, null,
                false, false, null, null, null, null, 0));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> time.get());
        service = new BrowserLoginService(jdbc, connections, clock, new DataSourceTransactionManager(source));
    }

    @AfterEach void close() { source.destroy(); }

    @Test void scopedJobClaimsAndCompletesOnlyForItsOwnerAndHelper() {
        var job = service.create(owner, account, new BrowserLoginService.StartRequest(0L, null));
        assertThat(job.state()).isEqualTo("WAITING_FOR_HELPER");
        assertThatThrownBy(() -> service.get(UUID.randomUUID(), job.id())).isInstanceOf(DomainException.class);
        service.claim(owner, job.id(), helper);
        assertThatThrownBy(() -> service.complete(owner, job.id(), new BrowserLoginService.CompleteRequest(
                UUID.randomUUID(), "Bearer synthetic-token", null, "synthetic-account"))).isInstanceOf(DomainException.class);
        var completed = service.complete(owner, job.id(), new BrowserLoginService.CompleteRequest(
                helper, "Bearer synthetic-token", "__Secure-next-auth.session-token=synthetic-cookie", "synthetic-account"));
        assertThat(completed.state()).isEqualTo("COMPLETED");
        verify(connections).save(eq(account), argThat(value -> value.version() == 0 && value.proxyNodeId() == null));
        verify(connections).refresh(account);
        assertThat(completed.toString()).doesNotContain("synthetic-token", "synthetic-cookie");
        assertThatThrownBy(() -> service.complete(owner, job.id(), new BrowserLoginService.CompleteRequest(
                helper, "Bearer synthetic-token", null, "synthetic-account"))).isInstanceOf(DomainException.class);
    }

    @Test void cancellationExpiryVersionAndConcurrencyFailClosed() {
        assertThatThrownBy(() -> service.create(owner, account, new BrowserLoginService.StartRequest(5L, null)))
                .isInstanceOf(DomainException.class);
        var job = service.create(owner, account, new BrowserLoginService.StartRequest(0L, null));
        assertThatThrownBy(() -> service.create(owner, account, new BrowserLoginService.StartRequest(0L, null)))
                .isInstanceOf(DomainException.class);
        assertThat(service.cancel(owner, job.id()).state()).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> service.claim(owner, job.id(), helper)).isInstanceOf(DomainException.class);
        var next = service.create(owner, account, new BrowserLoginService.StartRequest(0L, null));
        time.set(time.get().plusSeconds(601));
        assertThat(service.get(owner, next.id()).state()).isEqualTo("EXPIRED");
        assertThatThrownBy(() -> service.claim(owner, next.id(), helper)).isInstanceOf(DomainException.class);
        verify(connections, never()).save(any(), any());
    }

    @Test void onlyVerifiedRefreshCountsAsCompletedAndSecretsAreNeverSavedInJobs() {
        when(connections.refresh(account)).thenThrow(new DomainException("OFFICIAL_CHALLENGE_REQUIRED", "private-body",
                org.springframework.http.HttpStatus.BAD_GATEWAY));
        var job = service.create(owner, account, new BrowserLoginService.StartRequest(0L, null));
        service.claim(owner, job.id(), helper);
        var result = service.complete(owner, job.id(), new BrowserLoginService.CompleteRequest(
                helper, "Bearer synthetic-token", null, "synthetic-account"));
        assertThat(result.state()).isEqualTo("FAILED");
        assertThat(result.errorCode()).isEqualTo("OFFICIAL_CHALLENGE_REQUIRED");
        assertThat(jdbc.queryForMap("select * from official_browser_login_jobs where id=?", job.id()).toString())
                .doesNotContain("synthetic-token", "private-body");
    }

    @Test void expiredWriterCannotSaveCredentialsAfterItsReservationWasReleased() {
        Clock delayed = mock(Clock.class);
        when(delayed.instant()).thenAnswer(call -> time.get());
        var delayedService = new BrowserLoginService(jdbc,connections,delayed,new DataSourceTransactionManager(source));
        var job = delayedService.create(owner,account,new BrowserLoginService.StartRequest(0L,null));
        delayedService.claim(owner,job.id(),helper);
        Instant now = time.get();
        when(delayed.instant()).thenReturn(now,now,now,now.plusSeconds(121),now.plusSeconds(121));
        delayedService.complete(owner,job.id(),new BrowserLoginService.CompleteRequest(helper,"Bearer synthetic-token",null,"synthetic-account"));
        verify(connections,never()).save(any(),any());
        verify(connections,never()).refresh(any());
    }

    @Test void missingProxyIsRejectedBeforeAskingTheUserToLogIn() {
        assertThatThrownBy(() -> service.create(owner,account,new BrowserLoginService.StartRequest(0L,UUID.randomUUID())))
                .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo("OFFICIAL_PROXY_UNAVAILABLE"));
    }

    @Test void reservationRechecksOriginalDeadlineAfterWaitingForTheJobLock() {
        var job=service.create(owner,account,new BrowserLoginService.StartRequest(0L,null));
        service.claim(owner,job.id(),helper);
        Clock delayed=mock(Clock.class);
        when(delayed.instant()).thenReturn(time.get(),time.get().plusSeconds(601));
        var delayedService=new BrowserLoginService(jdbc,connections,delayed,new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> delayedService.complete(owner,job.id(),new BrowserLoginService.CompleteRequest(
                helper,"Bearer synthetic-token",null,"synthetic-account"))).isInstanceOf(DomainException.class);
        verify(connections,never()).save(any(),any());
    }

    @Test void claimRechecksDeadlineAfterWaitingForTheJobLock() {
        var job=service.create(owner,account,new BrowserLoginService.StartRequest(0L,null));
        Clock delayed=mock(Clock.class);
        when(delayed.instant()).thenReturn(time.get(),time.get().plusSeconds(601));
        var delayedService=new BrowserLoginService(jdbc,connections,delayed,new DataSourceTransactionManager(source));
        assertThatThrownBy(() -> delayedService.claim(owner,job.id(),helper)).isInstanceOf(DomainException.class);
    }
}
