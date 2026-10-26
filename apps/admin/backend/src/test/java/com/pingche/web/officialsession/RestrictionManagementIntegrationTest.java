package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.restriction.RestrictionManagementService;
import com.pingche.web.restriction.RestrictionScheduler;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:pingche-restrictions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RestrictionManagementIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");
    private static final String URL = "/api/v1/admin/restrictions";
    @Autowired MockMvc mvc;
    @Autowired RestrictionManagementService service;
    @Autowired com.pingche.web.restriction.StaleBindingCleanupService stale;
    @Autowired OfficialAccountRepository accounts;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository sessions;
    @Autowired OfficialConnectionService connections;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired com.pingche.web.quota.QuotaOverviewService quotas;
    @Autowired OfficialSessionLogoutService logout;
    @MockitoBean OfficialSessionClient client;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private User admin, member;
    private OfficialAccount account;
    private Team team;
    private UUID proxy;

    @BeforeEach void setup() {
        now.set(NOW); when(clock.instant()).thenAnswer(call -> now.get());
        jdbc.update("delete from restriction_executions");
        jdbc.update("delete from restriction_account_checks");
        jdbc.update("delete from stale_binding_checks");
        jdbc.update("delete from session_bindings");
        jdbc.update("delete from session_binding_attempts");
        jdbc.update("delete from official_session_logout_plans");
        sessions.deleteAll(); users.deleteAll(); teams.deleteAll(); accounts.deleteAll();
        jdbc.update("update restriction_policy set personal_quota_logout_enabled=false,unbound_cleanup_enabled=false,stale_binding_cleanup_enabled=true,unbound_interval_minutes=90,version=0,updated_by=null,updated_at=? where id=1", at(NOW));
        proxy = SyntheticProxyNodeFixture.insert(jdbc);
        admin = users.saveAndFlush(User.bootstrapAdministrator("restriction-admin@example.com", "unused", "Admin"));
        member = users.saveAndFlush(new User("restriction-member@example.com", "unused", "Member"));
        account = accounts.saveAndFlush(new OfficialAccount("Restriction account", 1000, admin.getId(), NOW));
        team = new Team("Restriction team", "", Team.Status.ACTIVE,100,100,NOW);
        team.updateTokenLimits(null,null,null,account.getId(),NOW); team = teams.saveAndFlush(team);
        jdbc.update("update users set team_id=? where id=?",team.getId(),member.getId());
        connections.save(account.getId(),new OfficialConnectionRequest("Bearer synthetic-restriction",null,"official-one",proxy,0L));
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenReturn(snapshot(List.of(device("bound",false,false),device("stranger",false,false))));
        when(client.revokeSession(any(OfficialSessionCredentials.class),eq(proxy),any(String.class))).thenReturn(new OfficialSessionWriteResult("ACCEPTED",null));
        connections.refresh(account.getId()); clearInvocations(client);
    }

    @Test void defaultsAreDisabledAndOnlyAdministratorsCanConfigureValidatedSettings() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION,token(member))).andExpect(status().isForbidden());
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION,token(admin))).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL,"no-store"))
                .andExpect(jsonPath("$.policy.personalQuotaLogoutEnabled").value(false))
                .andExpect(jsonPath("$.policy.unboundCleanupEnabled").value(false))
                .andExpect(jsonPath("$.policy.unboundCheckIntervalMinutes").value(90));
        mvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION,token(member)).contentType(MediaType.APPLICATION_JSON).content(settings(true,true,90,0)))
                .andExpect(status().isForbidden());
        for (int interval : List.of(29,1441)) mvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION,token(admin)).contentType(MediaType.APPLICATION_JSON).content(settings(true,true,interval,0)))
                .andExpect(status().isBadRequest());
        mvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION,token(admin)).with(request -> {request.setScheme("ftp");return request;})
                        .contentType(MediaType.APPLICATION_JSON).content(settings(true,true,90,0)))
                .andExpect(status().isBadRequest());
        service.checkAccount(account.getId()); assertThat(service.dueAccounts()).isEmpty(); verifyNoInteractions(client);
    }

    @Test void staleBindingCheckRunsEveryMinuteAndEndsBothTheBindingAndOldBoundAttempt() {
        binding("bound"); now.set(NOW.plusSeconds(60));
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenReturn(snapshot(List.of(device("new-login",false,false))));
        assertThat(stale.dueAccounts()).contains(account.getId()); stale.checkAccount(account.getId());
        assertThat(jdbc.queryForObject("select status from session_bindings where session_id='bound'",String.class)).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject("select count(*) from session_bindings where active_device_id is not null or active_session_id is not null",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from session_binding_attempts",String.class)).isEqualTo("REJECTED");
        assertThat(service.read().staleBindings().removedCount()).isEqualTo(1);
        assertThat(service.read().staleBindings().nextCheckAt()).isEqualTo(NOW.plusSeconds(120));
        verify(client,never()).revokeSession(any(OfficialSessionCredentials.class),any(),any());
        clearInvocations(client); stale.checkAccount(account.getId()); verifyNoInteractions(client);
    }

    @Test void liveBindingsArePreservedAndASecondCheckIsNotRunBeforeSixtySeconds() {
        binding("bound"); now.set(NOW.plusSeconds(60)); stale.checkAccount(account.getId());
        assertThat(jdbc.queryForObject("select status from session_bindings",String.class)).isEqualTo("ACTIVE");
        clearInvocations(client); now.set(NOW.plusSeconds(119)); stale.checkAccount(account.getId()); verifyNoInteractions(client);
        now.set(NOW.plusSeconds(120)); assertThat(stale.dueAccounts()).contains(account.getId());
    }

    @Test void failedIncompleteAndWrongIdentitySnapshotsNeverRemoveBindings() {
        binding("bound"); now.set(NOW.plusSeconds(60));
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenThrow(new com.pingche.web.auth.DomainException("OFFICIAL_RATE_LIMITED","Synthetic limit",org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));
        stale.checkAccount(account.getId());
        assertThat(service.read().staleBindings().nextCheckAt()).isEqualTo(NOW.plusSeconds(960));
        assertThat(jdbc.queryForObject("select status from session_bindings",String.class)).isEqualTo("ACTIVE");
        now.set(NOW.plusSeconds(960));
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenReturn(snapshot(List.of(device(null,false,false))));
        stale.checkAccount(account.getId());
        assertThat(service.read().staleBindings().lastErrorCode()).isEqualTo("STALE_BINDING_SNAPSHOT_INCOMPLETE");
        now.set(NOW.plusSeconds(1020));
        var normal=snapshot(List.of()); var a=normal.account();
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenReturn(new OfficialSessionSnapshot(
                new OfficialAccountInfo("another-owner",a.email(),a.userName(),a.accountId(),a.accountName(),a.planType(),a.accountUserRole(),a.deactivated(),a.activeSubscription()),normal.sessions()));
        stale.checkAccount(account.getId());
        assertThat(jdbc.queryForObject("select status from session_bindings",String.class)).isEqualTo("ACTIVE");
        verify(client,never()).revokeSession(any(OfficialSessionCredentials.class),any(),any());
    }

    @Test void freshBindingsCreatedDuringTheFetchAreNeverRemovedByAnOlderCheck() {
        binding("bound"); now.set(NOW.plusSeconds(60));
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenAnswer(call -> {
            binding("created-during-fetch"); return snapshot(List.of());
        });
        stale.checkAccount(account.getId());
        assertThat(jdbc.queryForObject("select status from session_bindings where session_id='bound'",String.class)).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject("select status from session_bindings where session_id='created-during-fetch'",String.class)).isEqualTo("ACTIVE");
    }

    @Test void aConnectionChangedDuringTheFetchPreservesTheOldRelationship() {
        binding("bound"); now.set(NOW.plusSeconds(60));
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenAnswer(call -> {
            jdbc.update("update official_account_connections set revision=? where official_account_id=?",UUID.randomUUID(),account.getId());
            return snapshot(List.of());
        });
        stale.checkAccount(account.getId());
        assertThat(jdbc.queryForObject("select status from session_bindings",String.class)).isEqualTo("ACTIVE");
    }

    @Test void staleCleanupIsEnabledByDefaultAndOnlyAdminsCanChangeItsVersionedSwitch() throws Exception {
        binding("bound");
        mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION,token(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.staleBindings.enabled").value(true)).andExpect(jsonPath("$.staleBindings.intervalMinutes").value(1));
        mvc.perform(put(URL+"/stale-bindings").header(HttpHeaders.AUTHORIZATION,token(member)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"version\":0}"))
                .andExpect(status().isForbidden());
        mvc.perform(put(URL+"/stale-bindings").header(HttpHeaders.AUTHORIZATION,token(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false,\"version\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.staleBindings.enabled").value(false)).andExpect(jsonPath("$.policy.version").value(1));
        now.set(NOW.plusSeconds(60)); stale.checkAccount(account.getId()); verifyNoInteractions(client);
        mvc.perform(put(URL+"/stale-bindings").header(HttpHeaders.AUTHORIZATION,token(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"version\":0}"))
                .andExpect(status().isConflict());
    }

    @Test void settingsPersistAndConcurrentEditorsCannotOverwriteThePolicy() throws Exception {
        mvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION,token(admin)).contentType(MediaType.APPLICATION_JSON).content(settings(true,true,90,0)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.policy.version").value(1));
        mvc.perform(put(URL).header(HttpHeaders.AUTHORIZATION,token(admin)).contentType(MediaType.APPLICATION_JSON).content(settings(false,false,90,0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESTRICTION_VERSION_CONFLICT"));
        var restarted = new RestrictionManagementService(jdbc,quotas,logout,clock,json,manager);
        assertThat(restarted.read().policy().unboundCleanupEnabled()).isTrue();
        assertThat(restarted.read().policy().unboundCheckIntervalMinutes()).isEqualTo(90);
        verifyNoInteractions(client);
    }

    @Test void exhaustedPersonalQuotaRevokesOnlyTheExactBindingEvenWhenTheDeviceIsTrusted() throws Exception {
        binding("bound"); jdbc.update("update users set weekly_usd_limit=0 where id=?",member.getId());
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenReturn(snapshot(List.of(device("bound",true,false),device("stranger",false,false),device(null,null,null))));
        enable(true,false); service.checkAccount(account.getId()); awaitFinished();
        verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound"));
        verify(client,never()).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("stranger"));
        assertThat(jdbc.queryForObject("select status from session_bindings where session_id='bound'",String.class)).isEqualTo("INVALIDATED");
        var execution = service.read().executions().get(0);
        assertThat(execution.status()).isEqualTo("ACCEPTED"); assertThat(execution.acceptedCount()).isEqualTo(1);
        String response = mvc.perform(get(URL).header(HttpHeaders.AUTHORIZATION,token(admin))).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-restriction","sessionId","authorization_cipher");
    }

    @Test void unsetPersonalLimitsAndExhaustedTeamLimitsDoNotTriggerPersonalEnforcement() {
        binding("bound"); jdbc.update("update teams set weekly_usd_limit=0 where id=?",team.getId());
        enable(true,false); service.checkAccount(account.getId());
        verifyNoInteractions(client); assertThat(service.read().executions()).isEmpty();
        assertThat(jdbc.queryForObject("select status from session_bindings where session_id='bound'",String.class)).isEqualTo("ACTIVE");
    }

    @Test void cleanupWaitsNinetyMinutesAndExcludesBoundCurrentTrustedUnknownAndDuplicateSessions() throws Exception {
        binding("bound");
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenReturn(snapshot(List.of(
                device("bound",false,false),device("stranger",false,false),device("trusted",true,false),
                device("current",false,true),device("unknown-trust",null,false),device("unknown-current",false,null),
                device("duplicate",false,false),device("duplicate",false,false))));
        enable(false,true); service.checkAccount(account.getId()); verifyNoInteractions(client);
        now.set(NOW.plusSeconds(5399)); service.checkAccount(account.getId()); verifyNoInteractions(client);
        now.set(NOW.plusSeconds(5400)); service.checkAccount(account.getId()); awaitFinished();
        verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("stranger"));
        verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),any(String.class));
        assertThat(service.read().executions().get(0).excludedCount()).isEqualTo(7);
        assertThat(jdbc.queryForObject("select status from session_bindings where session_id='bound'",String.class)).isEqualTo("ACTIVE");
        assertThat(service.read().accounts().get(0).nextUnboundAt()).isEqualTo(NOW.plusSeconds(10800));
        clearInvocations(client);
        var restarted = new RestrictionManagementService(jdbc,quotas,logout,clock,json,manager);
        now.set(NOW.plusSeconds(5500)); restarted.checkAccount(account.getId()); verifyNoInteractions(client);
    }

    @Test void changingADeviceToTrustedBeforeDispatchPreventsAutomaticCleanup() throws Exception {
        enable(false,true); service.checkAccount(account.getId()); now.set(NOW.plusSeconds(5400));
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenReturn(
                snapshot(List.of(device("stranger",false,false))),snapshot(List.of(device("stranger",true,false))));
        service.checkAccount(account.getId()); awaitFinished();
        verify(client,never()).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),any(String.class));
        assertThat(service.read().executions().get(0).status()).isEqualTo("FAILED");
    }

    @Test void resettingThePersonalLimitWhileTheWorkerIsReadingPreventsAStaleLogout() throws Exception {
        pauseWorkerAndChange(() -> jdbc.update("update users set weekly_usd_limit=null where id=?",member.getId()));
    }

    @Test void disablingThePolicyWhileTheWorkerIsReadingPreventsAStaleLogout() throws Exception {
        pauseWorkerAndChange(() -> service.configure(admin.getId(),new RestrictionManagementService.Settings(false,false,90,1L)));
    }

    @Test void anUnknownOfficialOutcomeRemainsFencedAcrossLaterChecks() throws Exception {
        binding("bound"); jdbc.update("update users set five_hour_usd_limit=0 where id=?",member.getId());
        when(client.revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound"))).thenReturn(new OfficialSessionWriteResult("UNKNOWN","OFFICIAL_LOGOUT_OUTCOME_UNKNOWN"));
        enable(true,false); service.checkAccount(account.getId()); awaitFinished();
        now.set(NOW.plusSeconds(86400)); service.checkAccount(account.getId());
        assertThat(service.read().executions().get(0).status()).isEqualTo("UNKNOWN");
        assertThat(service.read().accounts().get(0).lastErrorCode()).isEqualTo("OFFICIAL_LOGOUT_OUTCOME_UNKNOWN");
        verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound"));
    }

    @Test void theServerTimerEnforcesQuotaWithoutAnyClientOrAdministrativeHttpRequest() throws Exception {
        binding("bound"); jdbc.update("update users set weekly_usd_limit=0 where id=?",member.getId()); enable(true,false);
        RestrictionScheduler scheduler = new RestrictionScheduler(service,true);
        try {
            scheduler.start(); long end = System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while (jdbc.queryForObject("select count(*) from restriction_executions",Integer.class)==0 && System.nanoTime()<end) Thread.sleep(50);
            assertThat(jdbc.queryForObject("select count(*) from restriction_executions",Integer.class)).isEqualTo(1);
            awaitFinished(); verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound"));
        } finally { scheduler.close(); }
    }

    @Test void aProviderReadCooldownSurvivesProcessRestartAndPolicyEdits() {
        binding("bound"); jdbc.update("update users set weekly_usd_limit=0 where id=?",member.getId()); enable(true,false);
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenThrow(new OfficialRateLimitedException("3600",NOW));
        service.checkAccount(account.getId());
        assertThat(service.read().accounts().get(0).nextQuotaAt()).isEqualTo(NOW.plusSeconds(3600));
        service.configure(admin.getId(),new RestrictionManagementService.Settings(false,false,90,1L));
        service.configure(admin.getId(),new RestrictionManagementService.Settings(true,false,90,2L));
        var restarted = new RestrictionManagementService(jdbc,quotas,logout,clock,json,manager);
        now.set(NOW.plusSeconds(3599)); restarted.checkAccount(account.getId());
        assertThat(restarted.dueAccounts()).isEmpty();
        verify(client,times(1)).fetch(any(OfficialSessionCredentials.class),eq(proxy));
        verify(client,never()).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),any(String.class));
    }

    @Test void realUsageReportsTriggerThePersonalLimitOnlyAfterItHasBeenConsumed() throws Exception {
        binding("bound"); jdbc.update("update users set five_hour_usd_limit=100 where id=?",member.getId()); enable(true,false);
        jdbc.update("insert into usage_model_prices(model,input_price,output_price,cache_read_price,cache_write_price,fast_multiplier,updated_at,price_source) values('restriction-usd',1000000,0,0,0,1.5,?,'MANUAL')",at(NOW));
        service.checkAccount(account.getId()); verifyNoInteractions(client);
        for (int index = 0; index < 2; index++) {
            String event = "codex-local-usage:v1:" + (index == 0 ? "a" : "b").repeat(64);
            mvc.perform(post("/api/v1/client/usage/events").header(HttpHeaders.AUTHORIZATION,token(member)).contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("events",List.of(Map.of("eventId",event,"occurredAt",NOW.toString(),"totalTokens",index == 0 ? 80 : 30,"source","TOKEN_USAGE_RECORD","model","restriction-usd","inputTokens",index == 0 ? 80 : 30,"outputTokens",0,"cachedInputTokens",0,"cacheWriteInputTokens",0,"serviceTier","standard"))))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedEvents").value(1));
            now.set(NOW.plusSeconds(61L * (index + 1))); service.checkAccount(account.getId());
            if (index == 0) verifyNoInteractions(client);
        }
        awaitFinished(); verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound"));
    }

    @Test void separateServerInstancesCannotDispatchTheSameAutomaticLogoutTwice() throws Exception {
        binding("bound"); jdbc.update("update users set weekly_usd_limit=0 where id=?",member.getId()); enable(true,false);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger reads = new AtomicInteger();
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenAnswer(call -> {
            if (reads.incrementAndGet() == 1) { reading.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
            return snapshot(List.of(device("bound",false,false)));
        });
        var secondInstance = new RestrictionManagementService(jdbc,quotas,logout,clock,json,manager);
        CompletableFuture<Void> first = CompletableFuture.runAsync(() -> service.checkAccount(account.getId()));
        try {
            assertThat(reading.await(10,TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> second = CompletableFuture.runAsync(() -> secondInstance.checkAccount(account.getId()));
            release.countDown(); first.get(10,TimeUnit.SECONDS); second.get(10,TimeUnit.SECONDS); awaitFinished();
            verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound"));
            assertThat(service.read().executions()).hasSize(1);
        } finally { release.countDown(); }
    }

    @Test void aRejectedWriteKeepsTheOfficialRetryDeadlineAcrossLaterChecks() throws Exception {
        binding("bound"); jdbc.update("update users set weekly_usd_limit=0 where id=?",member.getId()); enable(true,false);
        when(client.revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound")))
                .thenReturn(new OfficialSessionWriteResult("REJECTED","OFFICIAL_RATE_LIMITED",NOW.plusSeconds(7200)));
        service.checkAccount(account.getId()); awaitFinished();
        now.set(NOW.plusSeconds(5400)); service.checkAccount(account.getId());
        assertThat(service.read().accounts().get(0).nextQuotaAt()).isEqualTo(NOW.plusSeconds(7200));
        assertThat(service.dueAccounts()).isEmpty();
        verify(client,times(1)).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),eq("bound"));
    }

    private void pauseWorkerAndChange(Runnable mutation) throws Exception {
        binding("bound"); jdbc.update("update users set weekly_usd_limit=0 where id=?",member.getId()); enable(true,false);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger reads = new AtomicInteger();
        when(client.fetch(any(OfficialSessionCredentials.class),eq(proxy))).thenAnswer(call -> {
            if (reads.incrementAndGet()==2) { reading.countDown(); assertThat(release.await(10,TimeUnit.SECONDS)).isTrue(); }
            return snapshot(List.of(device("bound",false,false)));
        });
        try { service.checkAccount(account.getId()); assertThat(reading.await(10,TimeUnit.SECONDS)).isTrue(); mutation.run(); }
        finally { release.countDown(); }
        awaitFinished(); verify(client,never()).revokeSession(any(OfficialSessionCredentials.class),eq(proxy),any(String.class));
        assertThat(jdbc.queryForObject("select status from session_bindings where session_id='bound'",String.class)).isEqualTo("ACTIVE");
    }

    private void enable(boolean quota, boolean cleanup) { service.configure(admin.getId(),new RestrictionManagementService.Settings(quota,cleanup,90,0L)); }
    @Test void personalIntervalIsConfigurableInMinutesAndSurvivesServiceRestart() {
        service.configure(admin.getId(),new RestrictionManagementService.Settings(true,false,90,0L,7));
        service.checkAccount(account.getId());
        assertThat(service.read().policy().personalCheckIntervalMinutes()).isEqualTo(7);
        assertThat(service.read().policy().quotaCheckIntervalSeconds()).isEqualTo(420);
        assertThat(service.read().accounts().get(0).nextQuotaAt()).isEqualTo(NOW.plusSeconds(420));
        service.configure(admin.getId(),new RestrictionManagementService.Settings(true,false,90,1L,15));
        var restarted=new RestrictionManagementService(jdbc,quotas,logout,clock,json,manager);
        assertThat(restarted.read().policy().personalCheckIntervalMinutes()).isEqualTo(15);
        now.set(NOW.plusSeconds(899)); assertThat(restarted.dueAccounts()).isEmpty();
        now.set(NOW.plusSeconds(900)); assertThat(restarted.dueAccounts()).contains(account.getId());
        verifyNoInteractions(client);
    }
    private String settings(boolean quota, boolean cleanup, int interval, long version) { return json.writeValueAsString(Map.of("personalQuotaLogoutEnabled",quota,"unboundCleanupEnabled",cleanup,"unboundCheckIntervalMinutes",interval,"personalCheckIntervalMinutes",1,"version",version)); }
    private String token(User user) { return "Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion()); }
    private static Object at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private void binding(String session) {
        UUID device=UUID.randomUUID(), attempt=UUID.randomUUID(), revision=jdbc.queryForObject("select revision from official_account_connections where official_account_id=?",UUID.class,account.getId());
        jdbc.update("insert into session_binding_attempts(id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values(?,?,?,?,?,?,?,?,'BOUND',?,?)",attempt,member.getId(),device,device,team.getId(),team.getVersion(),account.getId(),revision,at(NOW),at(NOW.plusSeconds(600)));
        jdbc.update("insert into session_bindings(id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) values(?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','SERIAL_LOGIN_DIFF','USER_CONFIRMED_INFERENCE',?)",UUID.randomUUID(),attempt,member.getId(),device,device,team.getId(),team.getVersion(),account.getId(),revision,session,device,session,at(NOW));
    }
    private void awaitFinished() throws InterruptedException {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(jdbc.queryForObject("select count(*) from official_session_logout_plans where status='EXECUTING'",Integer.class)>0 && System.nanoTime()<end)Thread.sleep(10);
        assertThat(jdbc.queryForObject("select count(*) from official_session_logout_plans where status='EXECUTING'",Integer.class)).isZero();
    }
    private OfficialSessionInfo device(String id,Boolean trusted,Boolean current) { return new OfficialSessionInfo("row-"+id,id,"windows","Synthetic device","PC","Windows","11",current,trusted,NOW,NOW.minusSeconds(100),null,null,null,List.of("Codex")); }
    private OfficialSessionSnapshot snapshot(List<OfficialSessionInfo> devices) { return new OfficialSessionSnapshot(new OfficialAccountInfo("official-owner","owner@example.com","Owner","official-one","Personal","plus","owner",false,true),new OfficialSessions(devices,true)); }
}
