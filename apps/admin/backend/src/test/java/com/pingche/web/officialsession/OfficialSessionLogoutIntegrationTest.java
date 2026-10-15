package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:pingche-official-logout;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfficialSessionLogoutIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired OfficialAccountRepository accounts;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository refreshSessions;
    @Autowired OfficialConnectionService connections;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockitoBean OfficialSessionClient client;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private OfficialAccount account;
    private User admin;
    private User otherAdmin;
    private User member;

    @BeforeEach
    void setup() {
        now.set(NOW);
        when(clock.instant()).thenAnswer(call -> now.get());
        jdbc.update("delete from session_bindings");
        jdbc.update("delete from session_binding_attempts");
        jdbc.update("delete from official_session_logout_plans");
        refreshSessions.deleteAll();
        users.deleteAll();
        teams.deleteAll();
        accounts.deleteAll();
        admin = users.saveAndFlush(User.bootstrapAdministrator("logout-admin@example.com", "unused-test-hash", "Admin"));
        otherAdmin = users.saveAndFlush(User.bootstrapAdministrator("logout-other@example.com", "unused-test-hash", "Other"));
        member = users.saveAndFlush(new User("logout-member@example.com", "unused-test-hash", "Member"));
        account = accounts.saveAndFlush(new OfficialAccount("Logout account", 1000, admin.getId(), NOW));
        connections.save(account.getId(), new OfficialConnectionRequest("Bearer synthetic-logout", null, "official-one", null, 0L));
        when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(device("s-one", false, false))));
    }

    @Test
    void previewRequiresAdministratorHttpsAndDoesNotExposeCredentials() throws Exception {
        mvc.perform(post(url("preview")).secure(true).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ALL\"}"))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mvc.perform(post(url("preview")).secure(true).header(HttpHeaders.AUTHORIZATION, token(member))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ALL\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(url("preview")).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ALL\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        verifyNoInteractions(client);
        String body = preview("ALL").toString();
        assertThat(body).doesNotContain("synthetic-logout", "authorizationCipher");
    }

    @Test
    void unboundPreviewIsConservativeAndExplainsEveryExclusion() throws Exception {
        when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(
                device("candidate", false, false), device("trusted", true, false), device("unknown", null, false),
                device("current", false, true), device("current-unknown", false, null), device(null, false, false),
                device("duplicate", false, false), device("duplicate", false, false))));
        JsonNode plan = preview("UNBOUND");
        assertThat(plan.path("targets").size()).isEqualTo(1);
        assertThat(plan.path("targets").get(0).path("sessionId").asString()).isEqualTo("candidate");
        assertThat(plan.path("excluded").size()).isEqualTo(7);
        assertThat(plan.toString()).contains("TRUSTED_DEVICE", "TRUSTED_DEVICE_UNKNOWN", "CURRENT_SESSION", "CURRENT_SESSION_UNKNOWN", "MISSING_SESSION_ID", "DUPLICATE_SESSION_ID");
    }

    @Test
    void confirmRequiresAcknowledgementOwnerAndUnexpiredPlan() throws Exception {
        String plan = preview("UNBOUND").path("planId").asString();
        confirm(admin, plan, false).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_ACK_REQUIRED"));
        confirm(otherAdmin, plan, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_PLAN_FORBIDDEN"));
        now.set(NOW.plusSeconds(121));
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_PLAN_EXPIRED"));
    }

    @Test
    void changedTrustedStatusRejectsWholePlanBeforeAnyOfficialWrite() throws Exception {
        String plan = preview("UNBOUND").path("planId").asString();
        when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(device("s-one", true, false))));
        confirm(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].errorCode").value("OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
        assertThat(org.mockito.Mockito.mockingDetails(client).getInvocations()).allMatch(invocation -> invocation.getMethod().getName().equals("fetch"));
    }

    @Test
    void allAcceptedInvalidatesBindingsClearsSnapshotAndCannotExecuteTwice() throws Exception {
        binding("s-bound");
        connections.refresh(account.getId());
        when(client.logoutAll(any(OfficialSessionCredentials.class), isNull())).thenReturn(new OfficialSessionWriteResult("ACCEPTED", null));
        String plan = preview("ALL").path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.requiresReauthorization").value(true));
        assertThat(jdbc.queryForObject("select status from official_account_connections where official_account_id=?", String.class, account.getId())).isEqualTo("AUTH_REQUIRED");
        assertThat(jdbc.queryForObject("select snapshot_cipher from official_account_connections where official_account_id=?", String.class, account.getId())).isNull();
        assertThat(jdbc.queryForObject("select count(*) from session_bindings where status='ACTIVE'", Integer.class)).isZero();
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_PLAN_CONSUMED"));
        verify(client, times(1)).logoutAll(any(OfficialSessionCredentials.class), isNull());
    }

    @Test
    void unboundPreservesActiveBindingsAndDoesNotExpandToNewSessions() throws Exception {
        binding("bound");
        UUID revision = jdbc.queryForObject("select revision from official_account_connections where official_account_id=?", UUID.class, account.getId());
        when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(device("candidate", false, false), device("bound", false, false))));
        when(client.revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("candidate"))).thenReturn(new OfficialSessionWriteResult("ACCEPTED", null));
        JsonNode plan = preview("UNBOUND");
        assertThat(plan.path("excluded").get(0).path("reason").asString()).isEqualTo("ACTIVE_BINDING");
        confirm(admin, plan.path("planId").asString(), true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(jdbc.queryForObject("select count(*) from session_bindings where status='ACTIVE'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select revision from official_account_connections where official_account_id=?", UUID.class, account.getId())).isEqualTo(revision);
        verify(client, times(1)).revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("candidate"));
        String next = preview("UNBOUND").path("planId").asString();
        when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(device("candidate", false, false), device("bound", false, false), device("new", false, false))));
        confirm(admin, next, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.results[0].errorCode").value("OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
        verify(client, times(1)).revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("candidate"));
    }

    @Test
    void partialRejectionStopsImmediatelyAndNeverLeaksProviderErrorText() throws Exception {
        when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(device("one", false, false), device("two", false, false), device("three", false, false))));
        when(client.revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("one"))).thenReturn(new OfficialSessionWriteResult("ACCEPTED", null));
        when(client.revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("two"))).thenReturn(new OfficialSessionWriteResult("REJECTED", "OFFICIAL_LOGOUT_REJECTED"));
        String plan = preview("UNBOUND").path("planId").asString();
        String body = confirm(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.results[2].status").value("NOT_ATTEMPTED")).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("private-upstream-response");
        assertThat(org.mockito.Mockito.mockingDetails(client).getInvocations().stream().filter(invocation -> invocation.getMethod().getName().equals("revokeSession")).count()).isEqualTo(2);
    }

    @Test
    void sessionBecomingTrustedDuringTheBatchStopsBeforeItsPost() throws Exception {
        when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(device("one", false, false), device("two", false, false))));
        when(client.revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("one"))).thenAnswer(call -> {
            when(client.fetch(any(OfficialSessionCredentials.class), isNull())).thenReturn(snapshot(List.of(device("two", true, false))));
            return new OfficialSessionWriteResult("ACCEPTED", null);
        });
        String plan = preview("UNBOUND").path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.results[1].status").value("NOT_ATTEMPTED"))
                .andExpect(jsonPath("$.results[1].errorCode").value("OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
        assertThat(org.mockito.Mockito.mockingDetails(client).getInvocations().stream().filter(invocation -> invocation.getMethod().getName().equals("revokeSession")).count()).isEqualTo(1);
    }

    @Test
    void unknownRemainsFencedUntilExplicitOwnerAcknowledgementWithoutAnotherOfficialPost() throws Exception {
        when(client.logoutAll(any(OfficialSessionCredentials.class), isNull())).thenThrow(new IllegalStateException("private-wire-error"));
        String plan = preview("ALL").path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.planId").value(plan)).andExpect(jsonPath("$.operation.canAcknowledge").value(true));
        mvc.perform(post(url("preview")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ALL\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_BUSY"));
        acknowledge(member, plan, true).andExpect(status().isForbidden());
        acknowledge(admin, plan, false).andExpect(status().isConflict());
        jdbc.update("update official_session_logout_plans set owner_id=? where id=?", UUID.randomUUID(), UUID.fromString(plan));
        acknowledge(otherAdmin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED_UNKNOWN"));
        verify(client, times(1)).logoutAll(any(OfficialSessionCredentials.class), isNull());
        assertThat(jdbc.queryForObject("select acknowledged_at is not null from official_session_logout_plans where id=?", Boolean.class, UUID.fromString(plan))).isTrue();
        assertThat(jdbc.queryForObject("select acknowledged_by from official_session_logout_plans where id=?", UUID.class, UUID.fromString(plan))).isEqualTo(otherAdmin.getId());
    }

    @Test
    void concurrentConfirmationCannotDispatchTheSamePlanTwice() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("s-one"))).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new OfficialSessionWriteResult("ACCEPTED", null);
        });
        String plan = preview("UNBOUND").path("planId").asString();
        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> {
            try { return confirm(admin, plan, true).andReturn().getResponse().getStatus(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> {
            try { return confirm(admin, plan, true).andReturn().getResponse().getStatus(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        release.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(409);
        verify(client, times(1)).revokeSession(any(OfficialSessionCredentials.class), isNull(), eq("s-one"));
    }

    @Test
    void acknowledgementWaitsForTheBoundedPostAndLateCompletionCannotRestoreTheConnection() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.logoutAll(any(OfficialSessionCredentials.class), isNull())).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return new OfficialSessionWriteResult("UNKNOWN", "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN");
        });
        String plan = preview("ALL").path("planId").asString();
        mvc.perform(post(url("confirm")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("planId", plan, "acknowledgeLogoutRisk", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTING"));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(jdbc.queryForObject("select results_json from official_session_logout_plans where id=?", String.class, UUID.fromString(plan))).contains("UNKNOWN");
        now.set(NOW.plusSeconds(361));
        CompletableFuture<Integer> acknowledgement = CompletableFuture.supplyAsync(() -> {
            try { return acknowledge(admin, plan, true).andReturn().getResponse().getStatus(); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        });
        Thread.sleep(50);
        assertThat(acknowledgement.isDone()).isFalse();
        release.countDown();
        assertThat(acknowledgement.get(10, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(jdbc.queryForObject("select status from official_session_logout_plans where id=?", String.class, UUID.fromString(plan))).isEqualTo("RESOLVED_UNKNOWN");
        assertThat(jdbc.queryForObject("select status from official_account_connections where official_account_id=?", String.class, account.getId())).isEqualTo("AUTH_REQUIRED");
        verify(client, times(1)).logoutAll(any(OfficialSessionCredentials.class), isNull());
    }

    @Test
    void changedBindingAndConnectionRevisionRejectPlansBeforeWrites() throws Exception {
        String plan = preview("UNBOUND").path("planId").asString();
        binding("s-one");
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
        jdbc.update("delete from session_bindings");
        String next = preview("UNBOUND").path("planId").asString();
        connections.save(account.getId(), new OfficialConnectionRequest("Bearer replacement", null, "official-one", null, connections.read(account.getId()).version()));
        confirm(admin, next, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
        assertThat(org.mockito.Mockito.mockingDetails(client).getInvocations()).allMatch(invocation -> invocation.getMethod().getName().equals("fetch"));
    }

    @Test
    void interruptedExecutionCannotBeReleasedUntilLeaseAndGraceHaveElapsed() throws Exception {
        String plan = preview("UNBOUND").path("planId").asString();
        jdbc.update("update official_session_logout_plans set status='EXECUTING',lease_until=? where id=?", OffsetDateTime.ofInstant(NOW.plusSeconds(300), ZoneOffset.UTC), UUID.fromString(plan));
        acknowledge(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_STILL_RUNNING"));
        now.set(NOW.plusSeconds(361));
        acknowledge(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED_UNKNOWN"));
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_PLAN_CONSUMED"));
    }

    private void binding(String session) {
        UUID id = UUID.randomUUID(), device = UUID.randomUUID(), team = UUID.randomUUID();
        UUID revision = jdbc.queryForObject("select revision from official_account_connections where official_account_id=?", UUID.class, account.getId());
        jdbc.update("insert into session_binding_attempts(id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values(?,?,?,?,?,0,?,?,'BOUND',?,?)", id, admin.getId(), device, device, team, account.getId(), revision, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), OffsetDateTime.ofInstant(NOW.plusSeconds(600), ZoneOffset.UTC));
        jdbc.update("insert into session_bindings(id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) values(?,?,?,?,?,?,0,?,?,?,?,?,'ACTIVE','SERIAL_LOGIN_DIFF','USER_CONFIRMED_INFERENCE',?)", UUID.randomUUID(), id, admin.getId(), device, device, team, account.getId(), revision, session, device, session, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    private org.springframework.test.web.servlet.ResultActions acknowledge(User owner, String planId, boolean acknowledge) throws Exception {
        return mvc.perform(post(url(planId + "/acknowledge-unknown")).secure(true).header(HttpHeaders.AUTHORIZATION, token(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("acknowledgeUncertainOutcome", acknowledge))));
    }

    private JsonNode preview(String mode) throws Exception {
        String body = mvc.perform(post(url("preview")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("mode", mode))))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(User owner, String planId, boolean acknowledge) throws Exception {
        var response = mvc.perform(post(url("confirm")).secure(true).header(HttpHeaders.AUTHORIZATION, token(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId", planId, "acknowledgeLogoutRisk", acknowledge))));
        if (response.andReturn().getResponse().getStatus() != 200) return response;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while ("EXECUTING".equals(jdbc.queryForObject("select status from official_session_logout_plans where id=?", String.class, UUID.fromString(planId))) && System.nanoTime() < deadline) Thread.sleep(10);
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url(planId)).secure(true).header(HttpHeaders.AUTHORIZATION, token(owner)));
    }

    private OfficialSessionInfo device(String id, Boolean trusted, Boolean current) {
        return new OfficialSessionInfo(id == null ? "missing" : "row-" + id, id, "windows", "Codex desktop", "PC", "Windows PC", "11",
                current, trusted, NOW, NOW.minusSeconds(100), "Synthetic City", "SC", "ZZ", List.of("Codex"));
    }

    private OfficialSessionSnapshot snapshot(List<OfficialSessionInfo> devices) {
        return new OfficialSessionSnapshot(new OfficialAccountInfo("official-owner", "owner@example.com", "Owner", "official-one", "Personal", "plus", "owner", false, true), new OfficialSessions(devices, true));
    }
    private String url(String action) { return "/api/v1/admin/official-accounts/" + account.getId() + "/session-logout/" + action; }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }
}
