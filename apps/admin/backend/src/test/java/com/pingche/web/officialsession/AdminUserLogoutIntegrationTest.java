package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:pingche-admin-user-logout;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserLogoutIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T10:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository refreshSessions;
    @Autowired OfficialAccountRepository accounts;
    @Autowired OfficialConnectionService connections;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @MockitoBean OfficialSessionClient client;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private User admin;
    private User otherAdmin;
    private User member;
    private User other;
    private OfficialAccount account;

    @BeforeEach void setup() {
        now.set(NOW);
        when(clock.instant()).thenAnswer(call -> now.get());
        jdbc.update("delete from session_bindings");
        jdbc.update("delete from session_binding_attempts");
        jdbc.update("delete from official_session_logout_plans");
        refreshSessions.deleteAll();
        users.deleteAll();
        accounts.deleteAll();
        admin = users.saveAndFlush(User.bootstrapAdministrator("user-logout-admin@example.com", "test", "Admin"));
        otherAdmin = users.saveAndFlush(User.bootstrapAdministrator("user-logout-admin2@example.com", "test", "Other Admin"));
        member = users.saveAndFlush(new User("user-logout-member@example.com", "test", "Member"));
        other = users.saveAndFlush(new User("user-logout-other@example.com", "test", "Other"));
        account = account("first");
        when(client.fetch(any(), isNull())).thenAnswer(call -> snapshot(call.getArgument(0), List.of("owned", "owned-two", "other", "unbound")));
        when(client.revokeSession(any(), isNull(), anyString())).thenReturn(new OfficialSessionWriteResult("ACCEPTED", null));
    }

    @Test void previewIncludesOnlyThisUsersDeviceSessionsAcrossAccountsAndDoesNotLogout() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        binding(other, account, "other", "ACTIVE");
        OfficialAccount second = account("second");
        binding(member, second, "owned-two", "ACTIVE");
        JsonNode preview = preview();
        assertThat(preview.path("scope").asString()).isEqualTo("ALL_ACCOUNTS");
        assertThat(preview.path("accounts").size()).isEqualTo(2);
        assertThat(preview.toString()).contains("owned", "owned-two").doesNotContain("\"sessionId\":\"other\"", "unbound", "Bearer synthetic");
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isZero();
        verify(client, never()).revokeSession(any(), any(), anyString());
        verify(client, never()).logoutAll(any(), any());
    }

    @Test void productOnlyEndpointRetainsCompatibilityAndDoesNotTouchOfficialOrMeterState() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        UUID meter = meterDevice();
        mvc.perform(post("/api/v1/admin/users/{id}/force-logout", member.getId()).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isNoContent());
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isEqualTo(1);
        assertThat(bindingStatus("owned")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from meter_devices where id=?", String.class, meter)).isEqualTo("ACTIVE");
        verify(client, never()).fetch(any(), any());
        verify(client, never()).revokeSession(any(), any(), anyString());
        verify(client, never()).logoutAll(any(), any());
    }

    @Test void endpointsRequireAdminSupportedTransportAndNoStore() throws Exception {
        mvc.perform(post(url("preview")).secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(post(url("preview")).secure(true).header(HttpHeaders.AUTHORIZATION, token(member))).andExpect(status().isForbidden());
        mvc.perform(post(url("preview")).with(http -> { http.setScheme("ftp"); return http; }).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        mvc.perform(get(url(UUID.randomUUID().toString())).secure(true).header(HttpHeaders.AUTHORIZATION, token(member)))
                .andExpect(status().isForbidden());
        preview();
        verify(client, never()).logoutAll(any(), any());
    }

    @Test void confirmationRequiresAcknowledgementOwnerTargetAndUnexpiredPlan() throws Exception {
        String plan = preview().path("planId").asString();
        confirm(admin, plan, false).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_LOGOUT_ACK_REQUIRED"));
        confirm(otherAdmin, plan, true).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("USER_LOGOUT_PLAN_FORBIDDEN"));
        mvc.perform(get("/api/v1/admin/users/{id}/force-logout-all/{plan}", other.getId(), plan).secure(true)
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isForbidden());
        now.set(NOW.plusSeconds(121));
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_LOGOUT_PLAN_EXPIRED"));
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isZero();
    }

    @Test void changedUserBindingSetInvalidatesConfirmationBeforeProductLogout() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        String plan = preview().path("planId").asString();
        binding(member, account, "owned-two", "ACTIVE");
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_LOGOUT_CONTEXT_CHANGED"));
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isZero();
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void allScopeRevokesExactOwnedSessionsAndNeverSharedAccountLogout() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        binding(member, account, "owned-two", "ACTIVE");
        binding(other, account, "other", "ACTIVE");
        UUID meter = meterDevice();
        String oldToken = token(member);
        String plan = preview().path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        JsonNode result = awaitResult(plan);
        assertThat(result.path("status").asString()).isEqualTo("ACCEPTED");
        assertThat(result.path("systemStatus").asString()).isEqualTo("REVOKED");
        assertThat(bindingStatus("owned")).isEqualTo("INVALIDATED");
        assertThat(bindingStatus("other")).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from meter_devices where id=?", String.class, meter)).isEqualTo("ACTIVE");
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, oldToken)).andExpect(status().isUnauthorized());
        assertThat(users.findById(other.getId()).orElseThrow().getSessionVersion()).isZero();
        verify(client).revokeSession(any(), isNull(), eq("owned"));
        verify(client).revokeSession(any(), isNull(), eq("owned-two"));
        verify(client, never()).revokeSession(any(), any(), eq("other"));
        verify(client, never()).logoutAll(any(), any());
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_LOGOUT_PLAN_CONSUMED"));
        read(plan).andExpect(status().isOk());
        verify(client, times(2)).revokeSession(any(), isNull(), anyString());
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isEqualTo(1);
    }

    @Test void historicalBindingsAreNotInferredToRemainOwnedByThisUser() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        binding(member, account, "missing", "ENDED");
        binding(member, account, "other", "ENDED");
        binding(other, account, "other", "ACTIVE");
        binding(member, account, "owned-two", "ENDED");
        JsonNode preview = preview();
        assertThat(preview.toString()).doesNotContain("missing", "owned-two", "\"sessionId\":\"other\"");
        assertThat(preview.path("accounts").get(0).path("targets").size()).isEqualTo(1);
        String plan = preview.path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        JsonNode result = awaitResult(plan);
        assertThat(result.path("status").asString()).isEqualTo("ACCEPTED");
        verify(client, times(1)).revokeSession(any(), isNull(), eq("owned"));
        verify(client, never()).revokeSession(any(), isNull(), eq("other"));
        verify(client, never()).revokeSession(any(), isNull(), eq("owned-two"));
    }

    @Test void officialFailurePreservesTruthfulPerSessionResultsAndProductSuccess() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        binding(member, account, "owned-two", "ACTIVE");
        when(client.revokeSession(any(), isNull(), eq("owned"))).thenReturn(new OfficialSessionWriteResult("REJECTED", "OFFICIAL_LOGOUT_DENIED"));
        String plan = preview().path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        JsonNode result = awaitResult(plan);
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(result.path("systemStatus").asString()).isEqualTo("REVOKED");
        assertThat(result.toString()).contains("FAILED", "NOT_ATTEMPTED", "OFFICIAL_LOGOUT_DENIED");
        assertThat(bindingStatus("owned")).isEqualTo("ACTIVE");
        verify(client, never()).revokeSession(any(), isNull(), eq("owned-two"));
    }

    @Test void unavailableAccountDoesNotPreventOtherOwnedAccountLogout() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        OfficialAccount second = account("second");
        binding(member, second, "owned-two", "ACTIVE");
        jdbc.update("delete from official_account_connections where official_account_id=?", second.getId());
        JsonNode preview = preview();
        assertThat(preview.toString()).contains("OFFICIAL_LOGOUT_CONNECTION_UNAVAILABLE", "NOT_ATTEMPTED");
        String plan = preview.path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        JsonNode result = awaitResult(plan);
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        verify(client).revokeSession(any(), isNull(), eq("owned"));
        verify(client, never()).revokeSession(any(), isNull(), eq("owned-two"));
    }

    @Test void excludedCurrentOfficialSessionIsReportedNotAttempted() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        doReturn(new OfficialSessionSnapshot(
                new OfficialAccountInfo("official-owner", "owner@example.com", "Owner", "first", "Personal", "plus", "owner", false, true),
                new OfficialSessions(List.of(new OfficialSessionInfo("row-owned", "owned", "windows", "Codex", "PC", "PC", "11", true, false, NOW, NOW, "City", "SC", "ZZ", List.of("Codex"))), true)))
                .when(client).fetch(any(), isNull());
        JsonNode preview = preview();
        assertThat(preview.path("accounts").get(0).path("status").asString()).isEqualTo("NOT_ATTEMPTED");
        assertThat(preview.toString()).contains("CURRENT_SESSION");
        String plan = preview.path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        JsonNode result = awaitResult(plan);
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        assertThat(result.toString()).contains("NOT_ATTEMPTED", "CURRENT_SESSION");
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void missingSelectedSessionFailsClosedWithNoOfficialWrites() throws Exception {
        binding(member, account, "absent", "ACTIVE");
        JsonNode preview = preview();
        assertThat(preview.toString()).contains("OFFICIAL_LOGOUT_SELECTION_INVALID", "NOT_ATTEMPTED");
        assertThat(preview.path("accounts").get(0).path("planId").isNull()).isTrue();
        String plan = preview.path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        JsonNode result = awaitResult(plan);
        assertThat(result.path("accounts").get(0).path("results").get(0).path("sessionId").asString()).isEqualTo("absent");
        assertThat(result.path("status").asString()).isEqualTo("PARTIAL");
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void changedConnectionAfterPreviewRejectsBeforeAnyLogout() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        String plan = preview().path("planId").asString();
        jdbc.update("update official_account_connections set revision=? where official_account_id=?", UUID.randomUUID(), account.getId());
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_LOGOUT_CONTEXT_CHANGED"));
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isZero();
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void unknownOfficialOutcomeIsNotClaimedSuccessfulOrRetried() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        when(client.revokeSession(any(), isNull(), eq("owned"))).thenThrow(new IllegalStateException("secret-provider-payload"));
        String plan = preview().path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        JsonNode result = awaitResult(plan);
        assertThat(result.path("status").asString()).isEqualTo("UNKNOWN");
        assertThat(result.toString()).contains("OFFICIAL_LOGOUT_OUTCOME_UNKNOWN").doesNotContain("secret-provider-payload");
        read(plan).andExpect(status().isOk());
        verify(client, times(1)).revokeSession(any(), isNull(), eq("owned"));
    }

    @Test void userWithoutOfficialBindingsCanStillRevokeProductSessions() throws Exception {
        String plan = preview().path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void currentRestoresSubmittedOperationForSameAdministratorWithoutResubmitting() throws Exception {
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation").isEmpty());
        String plan = preview().path("planId").asString();
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation").isEmpty());
        confirm(admin, plan, true).andExpect(status().isOk());
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.planId").value(plan))
                .andExpect(jsonPath("$.operation.systemStatus").value("REVOKED"));
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(otherAdmin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation").isEmpty());
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isEqualTo(1);
    }

    @Test void existingChildSubmissionIsReadWithoutClaimingItWasNotAttempted() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        JsonNode preview = preview();
        String plan = preview.path("planId").asString();
        UUID child = UUID.fromString(preview.path("accounts").get(0).path("planId").asString());
        // Simulate a durable child journal committed through the original official API.
        // No worker or real upstream request is started by this test.
        jdbc.update("update official_session_logout_plans set status='EXECUTING',started_at=?,lease_until=?,results_json=? where id=?",
                at(NOW), at(NOW.plusSeconds(300)), json.writeValueAsString(List.of(new OfficialSessionLogoutResult.Item("owned", "UNKNOWN", "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN"))), child);
        confirm(admin, plan, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.accounts[0].status").value("EXECUTING"))
                .andExpect(jsonPath("$.accounts[0].results[0].status").value("UNKNOWN"));
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.accounts[0].status").value("EXECUTING"));
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void everyNewRouteRejectsUnsupportedTransportBeforeAnyAction() throws Exception {
        UUID plan = UUID.randomUUID();
        var calls = List.of(post(url("preview")), post(url("confirm")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("planId", plan, "acknowledgeLogoutRisk", true))),
                get(url("current")), get(url(plan.toString())));
        for (var call : calls) mvc.perform(call.header(HttpHeaders.AUTHORIZATION, token(admin)).with(request -> {
                    request.setScheme("ftp");
                    request.setRemoteAddr("203.0.113.9");
                    request.setServerName("admin.example.test");
                    return request;
                })).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        verify(client, never()).fetch(any(), any());
        assertThat(users.findById(member.getId()).orElseThrow().getSessionVersion()).isZero();
    }

    @Test void staleActiveBindingCannotClaimSessionOwnershipAfterAuthorizationReplacement() throws Exception {
        UUID binding = binding(member, account, "owned", "ACTIVE");
        jdbc.update("update session_bindings set connection_revision=? where id=?", UUID.randomUUID(), binding);
        JsonNode preview = preview();
        assertThat(preview.toString()).contains("USER_LOGOUT_BINDING_STALE", "NOT_ATTEMPTED");
        confirm(admin, preview.path("planId").asString(), true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"));
        verify(client, never()).fetch(any(), any());
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void interruptedSubmissionCanBeReadAfterExpiryButNeverResumed() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        String plan = preview().path("planId").asString();
        jdbc.update("update admin_user_logout_plans set status='DISPATCHING',system_status='REVOKED',started_at=? where id=?", at(NOW), UUID.fromString(plan));
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXECUTING"))
                .andExpect(jsonPath("$.accounts[0].status").value("NOT_ATTEMPTED"));
        now.set(NOW.plusSeconds(301));
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.accounts[0].status").value("NOT_ATTEMPTED"));
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.planId").value(plan));
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_LOGOUT_PLAN_CONSUMED"));
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void manuallyResolvedUnknownRetainsUncertainItemsButDoesNotBlockANewOperation() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        JsonNode preview = preview();
        String plan = preview.path("planId").asString();
        UUID child = UUID.fromString(preview.path("accounts").get(0).path("planId").asString());
        jdbc.update("update admin_user_logout_plans set status='SUBMITTED',system_status='REVOKED',started_at=? where id=?", at(NOW), UUID.fromString(plan));
        jdbc.update("update admin_user_logout_accounts set dispatch_status='SUBMITTED' where plan_id=?", UUID.fromString(plan));
        jdbc.update("update official_session_logout_plans set status='UNKNOWN',started_at=?,lease_until=?,results_json=? where id=?",
                at(NOW), at(NOW.plusSeconds(300)), json.writeValueAsString(List.of(new OfficialSessionLogoutResult.Item("owned", "UNKNOWN", "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN"))), child);
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"));
        mvc.perform(post("/api/v1/admin/official-accounts/{id}/session-logout/{plan}/acknowledge-unknown", account.getId(), child).secure(true)
                        .header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgeUncertainOutcome\":true}"))
                .andExpect(status().isOk());
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PARTIAL"))
                .andExpect(jsonPath("$.accounts[0].status").value("RESOLVED_UNKNOWN"))
                .andExpect(jsonPath("$.accounts[0].results[0].status").value("UNKNOWN"));
        now.set(NOW.plusSeconds(1));
        String next = preview().path("planId").asString();
        confirm(admin, next, true).andExpect(status().isOk());
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.planId").value(next));
        verify(client, never()).revokeSession(any(), any(), anyString());
    }

    @Test void committedChildrenRecoverWhenOnlyTheParentsFinalMarkerWasLost() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        String plan = preview().path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        assertThat(awaitResult(plan).path("status").asString()).isEqualTo("ACCEPTED");
        // Durable account dispatch journals and official outcomes survive this simulated crash.
        jdbc.update("update admin_user_logout_plans set status='DISPATCHING' where id=?", UUID.fromString(plan));
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        now.set(NOW.plusSeconds(301));
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        String next = preview().path("planId").asString();
        confirm(admin, next, true).andExpect(status().isOk());
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.planId").value(next));
        verify(client, times(1)).revokeSession(any(), isNull(), eq("owned"));
    }

    @Test void currentKeepsUnresolvedOfficialOutcomeAheadOfNewerCompletedProductLogout() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        when(client.revokeSession(any(), isNull(), eq("owned"))).thenReturn(new OfficialSessionWriteResult("UNKNOWN", "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN"));
        String unresolved = preview().path("planId").asString();
        confirm(admin, unresolved, true).andExpect(status().isOk());
        assertThat(awaitResult(unresolved).path("status").asString()).isEqualTo("UNKNOWN");
        now.set(NOW.plusSeconds(1));
        String newer = preview().path("planId").asString();
        confirm(admin, newer, true).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCEPTED"));
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.planId").value(unresolved))
                .andExpect(jsonPath("$.operation.status").value("UNKNOWN"));
        verify(client, times(1)).revokeSession(any(), isNull(), eq("owned"));
    }

    @Test void missingSubmittedJournalRemainsUnknownAndNeverTriggersRetry() throws Exception {
        binding(member, account, "owned", "ACTIVE");
        JsonNode preview = preview();
        String plan = preview.path("planId").asString();
        confirm(admin, plan, true).andExpect(status().isOk());
        assertThat(awaitResult(plan).path("status").asString()).isEqualTo("ACCEPTED");
        jdbc.update("delete from official_session_logout_plans where id=?", UUID.fromString(preview.path("accounts").get(0).path("planId").asString()));
        read(plan).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.accounts[0].results[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.accounts[0].errorCode").value("USER_LOGOUT_RESULT_UNAVAILABLE"));
        now.set(NOW.plusSeconds(1));
        String newer = preview().path("planId").asString();
        confirm(admin, newer, true).andExpect(status().isOk());
        mvc.perform(get(url("current")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.operation.planId").value(plan));
        verify(client, times(1)).revokeSession(any(), isNull(), eq("owned"));
    }

    private OfficialAccount account(String key) {
        OfficialAccount value = accounts.saveAndFlush(new OfficialAccount("Account " + key, 1000, null, NOW));
        connections.save(value.getId(), new OfficialConnectionRequest("Bearer synthetic-" + key, null, key, null, 0L));
        return value;
    }

    private UUID binding(User owner, OfficialAccount official, String session, String state) {
        UUID attempt = UUID.randomUUID(), device = UUID.randomUUID(), team = UUID.randomUUID(), id = UUID.randomUUID();
        UUID revision = jdbc.queryForObject("select revision from official_account_connections where official_account_id=?", UUID.class, official.getId());
        jdbc.update("insert into session_binding_attempts(id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values(?,?,?,?,?,0,?,?,'BOUND',?,?)",
                attempt, owner.getId(), device, device, team, official.getId(), revision, at(NOW), at(NOW.plusSeconds(600)));
        jdbc.update("insert into session_bindings(id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at,ended_at) values(?,?,?,?,?,?,0,?,?,?,?,?,?,'SERIAL_LOGIN_DIFF','USER_CONFIRMED_INFERENCE',?,?)",
                id, attempt, owner.getId(), device, device, team, official.getId(), revision, session,
                "ACTIVE".equals(state) ? device : null, "ACTIVE".equals(state) ? session : null, state, at(NOW), "ACTIVE".equals(state) ? null : at(NOW));
        return id;
    }

    private String bindingStatus(String session) {
        return jdbc.queryForObject("select status from session_bindings where session_id=?", String.class, session);
    }
    private UUID meterDevice() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into meter_devices(id,user_id,token_hash,status,created_at,expires_at) values(?,?,?,'ACTIVE',?,?)",
                id, member.getId(), UUID.randomUUID().toString().replace("-", "").repeat(2), at(NOW), at(NOW.plusSeconds(3600)));
        return id;
    }
    private JsonNode preview() throws Exception {
        return json.readTree(mvc.perform(post(url("preview")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString());
    }
    private ResultActions confirm(User actor, String plan, boolean acknowledgement) throws Exception {
        return mvc.perform(post(url("confirm")).secure(true).header(HttpHeaders.AUTHORIZATION, token(actor)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("planId", plan, "acknowledgeLogoutRisk", acknowledgement))));
    }
    private ResultActions read(String plan) throws Exception {
        return mvc.perform(get(url(plan)).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)));
    }
    private JsonNode awaitResult(String plan) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        JsonNode result;
        do {
            result = json.readTree(read(plan).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (!"EXECUTING".equals(result.path("status").asString())) return result;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Mock official logout did not finish");
    }
    private OfficialSessionSnapshot snapshot(OfficialSessionCredentials credentials, List<String> sessions) {
        return new OfficialSessionSnapshot(new OfficialAccountInfo("official-owner", "owner@example.com", "Owner", credentials.requestedAccountId(), "Personal", "plus", "owner", false, true),
                new OfficialSessions(sessions.stream().map(id -> new OfficialSessionInfo("row-" + id, id, "windows", "Codex desktop", "PC", "Windows PC", "11", false, false, NOW, NOW, "City", "SC", "ZZ", List.of("Codex"))).toList(), true));
    }
    private String url(String action) { return "/api/v1/admin/users/" + member.getId() + "/force-logout-all/" + action; }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }
    private OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
}
