package com.pingche.web.sessionbinding;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.officialsession.*;
import com.zaxxer.hikari.HikariDataSource;
import java.time.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.ObjectMapper;

class SessionBindingAutomaticQueueTest {
    private final Instant start=Instant.parse("2026-10-15T00:00:00Z");
    private final AtomicReference<Instant> now=new AtomicReference<>(start);
    private final AtomicReference<List<String>> sessions=new AtomicReference<>(List.of("existing"));
    private JdbcTemplate jdbc;
    private OfficialConnectionService official;
    private SessionBindingService service;
    private SessionBindingScheduler scheduler;
    private HikariDataSource source;
    private UUID account=UUID.randomUUID(),team=UUID.randomUUID();
    private Owner first,second;

    @BeforeEach void setup() {
        // Keep physical connections alive: H2's constant-set check constraints
        // retain their DDL session's comparator. A pool also permits real races.
        source=new HikariDataSource();
        source.setJdbcUrl("jdbc:h2:mem:automatic-binding-"+UUID.randomUUID()+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
        source.setUsername("sa");source.setPassword("");source.setMaximumPoolSize(8);source.setMinimumIdle(1);
        jdbc=new JdbcTemplate(source);
        jdbc.execute("create table official_accounts(id uuid primary key)");
        jdbc.execute("create table users(id uuid primary key,status varchar(16),team_id uuid,display_name varchar(120),email varchar(320))");
        jdbc.execute("create table teams(id uuid primary key,status varchar(16),official_account_id uuid,version bigint)");
        jdbc.execute("create table meter_devices(id uuid primary key,user_id uuid,status varchar(16),expires_at timestamp with time zone)");
        jdbc.execute("create table official_account_connections(official_account_id uuid primary key,requested_account_id varchar(160),status varchar(32),revision uuid)");
        jdbc.execute("create table official_session_logout_plans(official_account_id uuid,status varchar(32))");
        jdbc.update("insert into official_accounts values (?)",account);
        jdbc.update("insert into teams values (?,'ACTIVE',?,0)",team,account);
        jdbc.update("insert into official_account_connections values (?,'synthetic-account','CONNECTED',?)",account,UUID.randomUUID());
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V24__add_session_binding_queue.sql"),
                new ClassPathResource("db/migration/V29__automate_session_binding_handover.sql")).execute(source);
        first=owner();second=owner();
        official=mock(OfficialConnectionService.class);
        doAnswer(call -> snapshot(sessions.get())).when(official).refresh(account);
        Clock clock=mock(Clock.class);
        when(clock.instant()).thenAnswer(call -> now.get());
        service=new SessionBindingService(jdbc,official,clock,new ObjectMapper(),new DataSourceTransactionManager(source));
    }
    @AfterEach void close() {if(scheduler!=null)scheduler.close();if(source!=null)source.close();}

    @Test void queueLastsAnHourAndTheThirtyMinuteLoginLeaseStartsAtFreshReady() {
        var queued=create(first);
        assertThat(queued.expiresAt()).isEqualTo(start.plusSeconds(3600));
        now.set(start.plusSeconds(1200));
        service.advance(account);
        var ready=service.get(first.user(),queued.id());
        assertThat(ready.status()).isEqualTo("READY_FOR_LOGIN");
        assertThat(ready.expiresAt()).isEqualTo(now.get().plusSeconds(1800));
        assertThat(ready.loginAllowed()).isTrue();
        assertThat(ready.queueState()).isEqualTo("ACTIVE");
    }

    @Test void backgroundChecksKeepWaitingWithoutNewSessionsAndRespectTheTenSecondOfficialLimit() {
        var attempt=create(first);service.advance(account);
        for(int second=0;second<30;second++) {
            now.set(start.plusSeconds(second));service.advance(account);
            assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("READY_FOR_LOGIN");
            assertThat(service.accountState(account).activeAttemptId()).isEqualTo(attempt.id());
        }
        verify(official,times(3)).refresh(account);
        assertThat(jdbc.queryForObject("select count(*) from session_bindings",Integer.class)).isZero();
    }

    @Test void uniqueNewSessionIsRevalidatedThenAutomaticallyBoundAndTheNextOwnerProgresses() {
        var attempt=create(first);service.advance(account);var queued=create(second);
        sessions.set(List.of("existing","new-session"));
        now.set(start.plusSeconds(10));service.advance(account);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(service.current(first.user(),first.device(),first.device()).binding()).isNull();
        service.advance(account);verify(official,times(2)).refresh(account);
        now.set(start.plusSeconds(20));service.advance(account);
        var bound=service.get(first.user(),attempt.id());
        assertThat(bound.status()).isEqualTo("BOUND");
        assertThat(bound.binding().sessionId()).isEqualTo("new-session");
        assertThat(bound.binding().verificationLevel()).isEqualTo("AUTOMATIC_SERIAL_INFERENCE");
        assertThat(service.accountState(account).activeAttemptId()).isNull();
        service.advance(account);
        assertThat(service.get(second.user(),queued.id()).loginAllowed()).isTrue();
        assertThat(jdbc.queryForObject("select before_session_ids from session_binding_attempts where id=?",String.class,queued.id())).contains("new-session");
    }

    @Test void ambiguousBackgroundDetectionReleasesTheClaimWithoutBinding() {
        var attempt=create(first);service.advance(account);
        sessions.set(List.of("existing","one","two"));
        now.set(start.plusSeconds(10));service.advance(account);
        var rejected=service.get(first.user(),attempt.id());
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.errorCode()).isEqualTo("SESSION_BINDING_AMBIGUOUS");
        assertThat(rejected.binding()).isNull();
        assertThat(service.accountState(account).activeAttemptId()).isNull();
        assertThat(rejected.queueState()).isEqualTo("SETTLING");
    }

    @Test void aNewNonCodexSessionCannotBeAutomaticallyAttributedToTheDevice() {
        var attempt=create(first);service.advance(account);
        when(official.refresh(account)).thenAnswer(call -> new OfficialConnectionRefreshResponse(null,
                snapshot(List.of()).account(),new OfficialSessions(List.of(new OfficialSessionInfo("web","web","web","Web",null,null,null,false,false,start,start,null,null,null,List.of("ChatGPT"))),true),now.get()));
        now.set(start.plusSeconds(10));service.advance(account);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("REJECTED");
        assertThat(service.get(first.user(),attempt.id()).errorCode()).isEqualTo("SESSION_BINDING_INVALID_CANDIDATE");
        assertThat(jdbc.queryForObject("select count(*) from session_bindings",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"missing","different","multiple"})
    void automaticRevalidationNeverBindsAChangedCandidate(String change) {
        var attempt=create(first);service.advance(account);
        sessions.set(List.of("existing","candidate"));now.set(start.plusSeconds(10));service.advance(account);
        sessions.set(change.equals("missing")?List.of("existing"):change.equals("different")?List.of("existing","other"):List.of("existing","candidate","other"));
        now.set(start.plusSeconds(20));service.advance(account);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from session_bindings",Integer.class)).isZero();
        assertThat(service.accountState(account).activeAttemptId()).isNull();
    }

    @Test void backgroundSnapshotFailureRetriesWithCappedBackoffAndPreservesTheOriginalDeadline() {
        var attempt=create(first);service.advance(account);
        when(official.refresh(account)).thenThrow(new IllegalStateException("synthetic-private-provider-body"));
        now.set(start.plusSeconds(10));
        for(int failure=0;failure<7;failure++) {
            Instant checked=now.get();service.advance(account);
            var waiting=service.get(first.user(),attempt.id());
            assertThat(waiting.status()).isEqualTo("READY_FOR_LOGIN");
            assertThat(waiting.expiresAt()).isEqualTo(start.plusSeconds(1800));
            Instant retry=jdbc.queryForObject("select next_prepare_at from session_binding_accounts where official_account_id=?",OffsetDateTime.class,account).toInstant();
            assertThat(retry).isEqualTo(checked.plusSeconds(Math.min(10L << Math.min(failure,4),60L)));
            service.advance(account);verify(official,times(failure+2)).refresh(account);
            now.set(retry);
        }
        now.set(start.plusSeconds(1800));service.advance(account);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("EXPIRED");
        assertThat(service.accountState(account).activeAttemptId()).isNull();
    }

    @Test void backgroundIdentityFailureIsTerminalAndDoesNotRetryAsAnOutage() {
        var attempt=create(first);service.advance(account);
        when(official.refresh(account)).thenAnswer(call -> new OfficialConnectionRefreshResponse(null,
                new OfficialAccountInfo("another-user",null,null,"synthetic-account","Synthetic","plus","owner",false,true),snapshot(List.of("existing","candidate")).sessions(),now.get()));
        now.set(start.plusSeconds(10));service.advance(account);
        assertThat(service.get(first.user(),attempt.id()).errorCode()).isEqualTo("SESSION_BINDING_IDENTITY_CHANGED");
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("REJECTED");
    }

    @ParameterizedTest @ValueSource(strings={"UNAVAILABLE","RATE_LIMITED"})
    void transientOfficialConnectionStatusPreservesTheClaimAndCandidateUntilAHealthyRevalidation(String status) {
        var attempt=create(first);service.advance(account);
        sessions.set(List.of("existing","candidate"));now.set(start.plusSeconds(10));service.advance(account);
        when(official.refresh(account)).thenAnswer(call -> {
            // Mirror OfficialConnectionService.fail, including its persisted state change.
            jdbc.update("update official_account_connections set status=? where official_account_id=?",status,account);
            throw new DomainException(status.equals("RATE_LIMITED")?"OFFICIAL_RATE_LIMITED":"OFFICIAL_UNAVAILABLE","synthetic-private-body",HttpStatus.BAD_GATEWAY);
        });
        now.set(start.plusSeconds(20));service.advance(account);
        var waiting=service.get(first.user(),attempt.id());
        assertThat(waiting.status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(waiting.candidateSessionId()).isEqualTo("candidate");
        assertThat(waiting.expiresAt()).isEqualTo(start.plusSeconds(1800));
        assertThat(service.current(first.user(),first.device(),first.device()).attempt().id()).isEqualTo(attempt.id());
        assertThat(service.accountState(account).activeAttemptId()).isEqualTo(attempt.id());
        when(official.refresh(account)).thenAnswer(call -> {
            jdbc.update("update official_account_connections set status='CONNECTED' where official_account_id=?",account);
            return snapshot(sessions.get());
        });
        now.set(start.plusSeconds(30));service.advance(account);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("BOUND");
        assertThat(service.get(first.user(),attempt.id()).binding().sessionId()).isEqualTo("candidate");
    }

    @ParameterizedTest @ValueSource(strings={"AUTH_REQUIRED","ACCESS_BLOCKED","INVALID_RESPONSE"})
    void nonTransientConnectionStateRevokesAutomaticBindingEvenWithAUniqueCandidate(String status) {
        var attempt=create(first);service.advance(account);
        sessions.set(List.of("existing","candidate"));now.set(start.plusSeconds(10));service.advance(account);
        jdbc.update("update official_account_connections set status=? where official_account_id=?",status,account);
        now.set(start.plusSeconds(20));service.advance(account);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from session_bindings",Integer.class)).isZero();
        verify(official,times(2)).refresh(account);
    }

    @Test void separateInstancesCannotDuplicateTheActiveBackgroundCheck() throws Exception {
        var attempt=create(first);service.advance(account);
        var other=new SessionBindingService(jdbc,official,Clock.fixed(start.plusSeconds(10),ZoneOffset.UTC),new ObjectMapper(),new DataSourceTransactionManager(source));
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(official.refresh(account)).thenAnswer(call -> {entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("synthetic timeout");return snapshot(List.of("existing","candidate"));});
        now.set(start.plusSeconds(10));var running=CompletableFuture.runAsync(() -> service.advance(account));
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();other.advance(account);
            verify(official,times(2)).refresh(account);
        } finally {release.countDown();}
        running.get(5,TimeUnit.SECONDS);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("AWAITING_CONFIRMATION");
    }

    @Test void aCancelledBackgroundResponseCannotBindOrOverwriteTheNextClaim() throws Exception {
        var attempt=create(first);service.advance(account);
        sessions.set(List.of("existing","candidate"));now.set(start.plusSeconds(10));service.advance(account);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(official.refresh(account)).thenAnswer(call -> {entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("synthetic timeout");return snapshot(List.of("existing","candidate"));});
        now.set(start.plusSeconds(20));var running=CompletableFuture.runAsync(() -> service.advance(account));
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();service.cancel(first.user(),attempt.id());
        } finally {release.countDown();}
        running.get(5,TimeUnit.SECONDS);
        assertThat(service.get(first.user(),attempt.id()).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from session_bindings",Integer.class)).isZero();
        assertThat(service.accountState(account).activeAttemptId()).isNull();
    }

    @Test void cancellationRevokesTheClaimThenSettlesBeforeGrantingTheNextFreshBaseline() {
        var old=create(first);service.advance(account);
        var next=create(second);
        var cancelled=service.cancel(first.user(),old.id());
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.loginAllowed()).isFalse();
        assertThat(cancelled.queueState()).isEqualTo("SETTLING");
        assertThat(cancelled.retryAt()).isEqualTo(now.get().plusSeconds(30));
        assertThat(service.accountState(account).activeAttemptId()).isNull();
        assertThat(jdbc.queryForObject("select operation_id from session_binding_attempts where id=?",UUID.class,old.id())).isNull();
        now.set(start.plusSeconds(29));service.advance(account);
        assertThat(service.get(second.user(),next.id()).loginAllowed()).isFalse();
        sessions.set(List.of("existing","late-old-session"));
        now.set(start.plusSeconds(30));service.advance(account);
        var ready=service.get(second.user(),next.id());
        assertThat(ready.loginAllowed()).isTrue();
        assertThat(ready.quarantined()).isFalse();
        assertThat(jdbc.queryForObject("select before_session_ids from session_binding_attempts where id=?",String.class,next.id())).contains("late-old-session");
        sessions.set(List.of("existing","late-old-session","new-next-session"));
        assertThat(service.detect(second.user(),next.id()).candidateSessionId()).isEqualTo("new-next-session");
        assertThat(service.current(second.user(),second.device(),second.device()).binding()).isNull();
    }

    @Test void expiredLoginPermissionIsReleasedWithoutAnAdministratorOrClientRequest() {
        var old=create(first);service.advance(account);
        var next=create(second);
        now.set(start.plusSeconds(1801));service.advance(account);
        assertThat(service.get(first.user(),old.id()).status()).isEqualTo("EXPIRED");
        assertThat(service.accountState(account).activeAttemptId()).isNull();
        assertThat(service.accountState(account).queueState()).isEqualTo("SETTLING");
        now.set(now.get().plusSeconds(30));service.advance(account);
        assertThat(service.get(second.user(),next.id()).loginAllowed()).isTrue();
    }

    @Test void expiredQueuedAttemptNeverGetsALoginGrant() {
        var queued=create(first);
        now.set(start.plusSeconds(3601));service.advance(account);
        assertThat(service.get(first.user(),queued.id()).status()).isEqualTo("EXPIRED");
        verify(official,never()).refresh(any());
    }

    @Test void legacyQuarantineAutomaticallySettlesAndChecksFreshStateEvenWithoutAWaitingOwner() {
        var old=create(first);service.advance(account);service.cancel(first.user(),old.id());
        jdbc.update("update session_binding_accounts set settle_until=null,next_prepare_at=null,reason='SESSION_BINDING_CANCELLED' where official_account_id=?",account);
        service.advance(account);
        assertThat(service.accountState(account).queueState()).isEqualTo("SETTLING");
        now.set(start.plusSeconds(30));service.advance(account);
        assertThat(service.accountState(account).quarantined()).isFalse();
        assertThat(service.accountState(account).queueState()).isEqualTo("IDLE");
    }

    @Test void temporaryBaselineFailureRetriesTheSameHeadWithBoundedBackoff() {
        var queued=create(first);
        when(official.refresh(account)).thenThrow(new DomainException("OFFICIAL_UNAVAILABLE","private-provider-body",HttpStatus.BAD_GATEWAY));
        service.advance(account);
        var retry=service.get(first.user(),queued.id());
        assertThat(retry.status()).isEqualTo("QUEUED");
        assertThat(retry.queueState()).isEqualTo("RETRYING");
        assertThat(retry.retryAt()).isEqualTo(start.plusSeconds(5));
        assertThat(retry.loginAllowed()).isFalse();
        service.advance(account);verify(official,times(1)).refresh(account);
        now.set(start.plusSeconds(5));service.advance(account);
        assertThat(service.get(first.user(),queued.id()).retryAt()).isEqualTo(start.plusSeconds(15));
        doAnswer(call -> snapshot(sessions.get())).when(official).refresh(account);
        now.set(start.plusSeconds(15));service.advance(account);
        assertThat(service.get(first.user(),queued.id()).loginAllowed()).isTrue();
        assertThat(service.get(first.user(),queued.id()).errorCode()).isNull();
        assertThat(jdbc.queryForObject("select prepare_failures from session_binding_accounts where official_account_id=?",Integer.class,account)).isZero();
    }

    @Test void theServerSchedulerGrantsTheHeadWithoutAnyPreparePostOrReadPolling() {
        var queued=create(first);
        scheduler=new SessionBindingScheduler(jdbc,service,true);
        scheduler.start();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForObject(
                "select status from session_binding_attempts where id=?",String.class,queued.id())).isEqualTo("READY_FOR_LOGIN"));
        assertThat(service.get(first.user(),queued.id()).loginAllowed()).isTrue();
        verify(official,times(1)).refresh(account);
    }

    @Test void cancellationOfAQueuedNonOwnerDoesNotInvalidateTheActiveClaim() {
        var active=create(first);service.advance(account);
        var queued=create(second);
        long generation=jdbc.queryForObject("select generation from session_binding_accounts where official_account_id=?",Long.class,account);
        service.cancel(second.user(),queued.id());
        assertThat(service.accountState(account).activeAttemptId()).isEqualTo(active.id());
        assertThat(service.get(first.user(),active.id()).loginAllowed()).isTrue();
        assertThat(jdbc.queryForObject("select generation from session_binding_accounts where official_account_id=?",Long.class,account)).isEqualTo(generation);
    }

    @Test void oldInFlightBaselineCannotGrantOrOverwriteANewerClaimGeneration() throws Exception {
        var old=create(first);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicInteger calls=new AtomicInteger();
        when(official.refresh(account)).thenAnswer(call -> {
            if(calls.incrementAndGet()==1) {entered.countDown();if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("synthetic timeout");return snapshot(List.of("old-baseline"));}
            return snapshot(List.of("new-baseline"));
        });
        var pending=CompletableFuture.runAsync(() -> service.advance(account));
        UUID next;
        try {
            assertThat(entered.await(3,TimeUnit.SECONDS)).isTrue();
            service.cancel(first.user(),old.id());
            next=create(second).id();
            service.advance(account);
            assertThat(service.get(second.user(),next).loginAllowed()).isTrue();
        } finally {release.countDown();}
        pending.get(5,TimeUnit.SECONDS);
        assertThat(service.get(first.user(),old.id()).status()).isEqualTo("CANCELLED");
        assertThat(service.accountState(account).activeAttemptId()).isEqualTo(next);
        assertThat(jdbc.queryForObject("select before_session_ids from session_binding_attempts where id=?",String.class,next)).contains("new-baseline").doesNotContain("old-baseline");
    }

    @ParameterizedTest @ValueSource(ints={0,2})
    void failedOrAmbiguousDetectionReleasesItsClaimAndNeverAutomaticallyBinds(int candidates) {
        var old=create(first);service.advance(account);
        sessions.set(candidates==0?List.of("existing"):List.of("existing","candidate-one","candidate-two"));
        var rejected=service.detect(first.user(),old.id());
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.errorCode()).isEqualTo(candidates==0?"SESSION_BINDING_NO_NEW_SESSION":"SESSION_BINDING_AMBIGUOUS");
        assertThat(rejected.binding()).isNull();
        assertThat(service.accountState(account).activeAttemptId()).isNull();
        assertThat(rejected.queueState()).isEqualTo("SETTLING");
        now.set(start.plusSeconds(30));service.advance(account);
        assertThat(service.accountState(account).quarantined()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from session_bindings",Integer.class)).isZero();
    }

    @Test void anOfficialLogoutFenceStillPreventsBackgroundPreparation() {
        var queued=create(first);
        jdbc.update("insert into official_session_logout_plans values (?,'UNKNOWN')",account);
        assertThatThrownBy(() -> service.advance(account)).isInstanceOf(DomainException.class);
        verify(official,never()).refresh(any());
        assertThat(jdbc.queryForObject("select status from session_binding_attempts where id=?",String.class,queued.id())).isEqualTo("QUEUED");
    }

    @Test void repeatedFreshBaselineFailuresNeverRetryFasterThanTheCappedBackoffOrGrantLogin() {
        var queued=create(first);
        when(official.refresh(account)).thenThrow(new IllegalStateException("synthetic-private-provider-body"));
        for (int failure=0;failure<8;failure++) {
            Instant attempted=now.get();
            service.advance(account);
            var retry=service.get(first.user(),queued.id());
            assertThat(retry.loginAllowed()).isFalse();
            long expected=Math.min(5L << Math.min(failure,4),60L);
            assertThat(retry.retryAt()).isEqualTo(attempted.plusSeconds(expected));
            assertThat(retry.errorCode()).isEqualTo("SESSION_BINDING_SNAPSHOT_UNAVAILABLE");
            now.set(retry.retryAt());
        }
        assertThat(jdbc.queryForObject("select count(*) from session_bindings",Integer.class)).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"PREPARING","DETECTING"})
    void abandonedOperationLeaseRevokesItsTokenAndCannotHoldTheQueueForever(String status) {
        var attempt=create(first);
        if(status.equals("DETECTING"))service.advance(account);
        UUID operation=UUID.randomUUID();
        jdbc.update("update session_binding_accounts set active_attempt_id=? where official_account_id=?",attempt.id(),account);
        jdbc.update("update session_binding_attempts set status=?,operation_id=?,operation_started_at=? where id=?",status,operation,start.atOffset(ZoneOffset.UTC),attempt.id());
        now.set(start.plusSeconds(91));service.advance(account);
        var released=service.get(first.user(),attempt.id());
        assertThat(released.status()).isEqualTo(status.equals("PREPARING")?"QUEUED":"REJECTED");
        assertThat(released.queueState()).isEqualTo(status.equals("PREPARING")?"RETRYING":"SETTLING");
        assertThat(released.loginAllowed()).isFalse();
        assertThat(jdbc.queryForObject("select operation_id from session_binding_attempts where id=?",UUID.class,attempt.id())).isNull();
        assertThat(service.accountState(account).activeAttemptId()).isNull();
    }

    @Test void recoveryFailureKeepsTheGuardButRetriesWithoutManualAcknowledgement() {
        var attempt=create(first);service.advance(account);service.cancel(first.user(),attempt.id());
        when(official.refresh(account)).thenThrow(new IllegalStateException("synthetic-private-provider-body"));
        now.set(start.plusSeconds(30));service.advance(account);
        assertThat(service.accountState(account).quarantined()).isTrue();
        assertThat(service.accountState(account).queueState()).isEqualTo("RETRYING");
        assertThat(service.accountState(account).retryAt()).isEqualTo(start.plusSeconds(35));
        doAnswer(call -> snapshot(sessions.get())).when(official).refresh(account);
        now.set(start.plusSeconds(35));service.advance(account);
        assertThat(service.accountState(account).quarantined()).isFalse();
    }

    @Test void disabledOwnerIsRemovedBeforeBackgroundGrantAndNextOwnerCanProgress() {
        var old=create(first);var next=create(second);
        jdbc.update("update users set status='DISABLED' where id=?",first.user());
        service.advance(account);
        assertThat(jdbc.queryForObject("select status from session_binding_attempts where id=?",String.class,old.id())).isEqualTo("REJECTED");
        assertThat(service.get(second.user(),next.id()).loginAllowed()).isTrue();
        verify(official,times(1)).refresh(account);
    }

    private Owner owner() {
        UUID user=UUID.randomUUID(),device=UUID.randomUUID();
        jdbc.update("insert into users values (?,'ACTIVE',?,'Synthetic','synthetic@example.test')",user,team);
        jdbc.update("insert into meter_devices values (?,?,'ACTIVE',?)",device,user,start.plusSeconds(7200).atOffset(ZoneOffset.UTC));
        return new Owner(user,device);
    }
    private SessionBindingAttemptResponse create(Owner owner) {return service.create(owner.user(),owner.device(),owner.device(),null);}
    private OfficialConnectionRefreshResponse snapshot(List<String> ids) {
        var identity=new OfficialAccountInfo("synthetic-user",null,null,"synthetic-account","Synthetic","plus","owner",false,true);
        var devices=ids.stream().map(id -> new OfficialSessionInfo(id,id,"windows","Synthetic",null,null,null,false,false,start,start,null,null,null,List.of("Codex"))).toList();
        return new OfficialConnectionRefreshResponse(null,identity,new OfficialSessions(devices,true),now.get());
    }
    private record Owner(UUID user,UUID device) { }
}
