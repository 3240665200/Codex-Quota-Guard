package com.pingche.web.quota;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthResponse;
import com.pingche.web.auth.AuthService;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.sessionbinding.ClientBindingFixture;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Instant;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaPolicyIntegrationTest {

    private static final String PASSWORD = "test-password-with-sufficient-length";
    private static final String OVERVIEW = "/api/v1/client/quota/overview";
    private static final String EVENT_ID = "codex-local-usage:v1:" + "a".repeat(64);

    @Autowired private MockMvc mvc;
    @Autowired private AuthService authService;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;
    @Autowired private RefreshSessionRepository refreshSessions;
    @Autowired private TeamRepository teams;
    @Autowired private CodexUsageEventRepository events;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        ClientBindingFixture.clearBindings(jdbc);
        events.deleteAll();
        refreshSessions.deleteAll();
        users.deleteAll();
        teams.deleteAll();
    }

    @AfterEach
    void clearBindingPrerequisites() { ClientBindingFixture.clearBindings(jdbc); }

    @Test
    void tokenLimitEndpointsRequireAdminAndCorsAllowsPut() throws Exception {
        AuthResponse ordinarySession = authService.register(
                "ordinary@example.com", PASSWORD, "Ordinary");
        User ordinary = users.findByEmail("ordinary@example.com").orElseThrow();

        mvc.perform(post("/api/v1/admin/teams")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinarySession.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(teamRequest("Ordinary team")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/users/{id}/token-limits", ordinary.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinarySession.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiveHourTokenLimit\":100,\"weeklyTokenLimit\":500}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users/{id}/quota-overview", ordinary.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinarySession.accessToken())))
                .andExpect(status().isForbidden());
        mvc.perform(options("/api/v1/admin/users/{id}/token-limits", ordinary.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:1420")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsString("PUT")));
    }

    @Test
    void managedCredentialDownloadIsNotAvailableToSignedInMembers() throws Exception {
        User admin = administrator();
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(admin, "Engineering");
        setTeamLimits(admin, teamId, 500, 1_000);
        joinTeam(admin, member.getId(), teamId);
        AuthResponse memberSession = authService.login("member@example.com", PASSWORD);

        mvc.perform(post("/api/v1/client/codex/credential")
                        .with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/client/codex/credential"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void legacyManagedAccountAdministrationIsNotRegistered() throws Exception {
        User admin = administrator();
        mvc.perform(get("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void editingTeamThenSavingTokenLimitsAcceptsTheReturnedVersion() throws Exception {
        User admin = administrator();
        UUID teamId = createTeam(admin, "Versioned team");
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        joinTeam(admin, member.getId(), teamId);
        long previousVersion = teams.findById(teamId).orElseThrow().getVersion();

        MvcResult edited = mvc.perform(patch("/api/v1/admin/teams/{id}", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Versioned team","description":"Managed policy team",
                                 "status":"ACTIVE","version":%d}
                                """.formatted(previousVersion)))
                .andExpect(status().isOk())
                .andReturn();
        long returnedVersion = objectMapper.readTree(edited.getResponse().getContentAsString())
                .path("version").asLong();

        mvc.perform(put("/api/v1/admin/teams/{id}/token-limits", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fiveHourTokenLimit":1000000,"weeklyTokenLimit":5000000,
                                 "version":%d}
                                """.formatted(returnedVersion)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiveHourTokenLimit").value(1000000))
                .andExpect(jsonPath("$.weeklyTokenLimit").value(5000000));
    }

    @Test
    void teamAndPersonalTokenLimitsCountReportedEventsAndInvalidateSessions() throws Exception {
        User admin = administrator();
        AuthResponse oldMemberSession = authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(admin, "Engineering");
        setTeamLimits(admin, teamId, 500, 1_000);
        joinTeam(admin, member.getId(), teamId);

        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(oldMemberSession.accessToken())))
                .andExpect(status().isUnauthorized());
        AuthResponse memberSession = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codexAccount").doesNotExist())
                .andExpect(jsonPath("$.assigned").doesNotExist())
                .andExpect(jsonPath("$.policy.fiveHourTokenLimit").value(nullValue()))
                .andExpect(jsonPath("$.policy.weeklyTokenLimit").value(nullValue()))
                .andExpect(jsonPath("$.policy.fiveHourSource").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.usage.measurementStatus").value("CLIENT_REPORTED"))
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(0))
                .andExpect(jsonPath("$.usage.measuredAt").value(nullValue()))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.fiveHourRemainingTokens").value(500))
                .andExpect(jsonPath("$.allowed").value(true));

        String event = usageRequest(EVENT_ID, 120);
        reportAndReadQuota(memberSession.accessToken(), event)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(120))
                .andExpect(jsonPath("$.usage.measuredAt").exists())
                .andExpect(jsonPath("$.usage.weeklyRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.weeklyRemainingTokens").value(880));
        reportAndReadQuota(memberSession.accessToken(), event)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(120));

        mvc.perform(put("/api/v1/admin/users/{id}/token-limits", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiveHourTokenLimit\":100,\"weeklyTokenLimit\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiveHourTokenLimit").value(100));
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isUnauthorized());
        AuthResponse afterOverride = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(afterOverride.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.fiveHourSource").value("USER"))
                .andExpect(jsonPath("$.policy.weeklySource").value("UNCONFIGURED"))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(0))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.blockReason").isEmpty());
        reportAndReadQuota(afterOverride.accessToken(), usageRequest(
                                "codex-local-usage:v1:" + "b".repeat(64), 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(140));
    }

    @Test
    void teamQuotaCountsAllMembersAndKeepsHistoricalTeamWhenMemberMoves() throws Exception {
        User admin = administrator();
        authService.register("alice@example.com", PASSWORD, "Alice");
        authService.register("bob@example.com", PASSWORD, "Bob");
        User alice = users.findByEmail("alice@example.com").orElseThrow();
        User bob = users.findByEmail("bob@example.com").orElseThrow();
        UUID firstTeam = createTeam(admin, "Shared team");
        UUID secondTeam = createTeam(admin, "New team");
        setTeamLimits(admin, firstTeam, 500, 1_000);
        setTeamLimits(admin, secondTeam, 500, 1_000);
        joinTeam(admin, alice.getId(), firstTeam);
        joinTeam(admin, bob.getId(), firstTeam);
        mvc.perform(put("/api/v1/admin/users/{id}/token-limits", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiveHourTokenLimit\":1000,\"weeklyTokenLimit\":2000}"))
                .andExpect(status().isOk());
        AuthResponse aliceSession = authService.login("alice@example.com", PASSWORD);
        AuthResponse bobSession = authService.login("bob@example.com", PASSWORD);

        mvc.perform(post("/api/v1/client/usage/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceSession.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(usageRequest(EVENT_ID, 280)))
                .andExpect(status().isOk());
        reportAndReadQuota(bobSession.accessToken(), usageRequest("codex-local-usage:v1:" + "b".repeat(64), 230))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(230))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(510))
                .andExpect(jsonPath("$.teamQuota.fiveHourRemainingTokens").value(0))
                .andExpect(jsonPath("$.blockReason").isEmpty());
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(280))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(720))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(510))
                .andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(get("/api/v1/admin/teams/{id}/quota-overview", firstTeam)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiveHourUsedTokens").value(510))
                .andExpect(jsonPath("$.weeklyRemainingTokens").value(490));

        reportAndReadQuota(bobSession.accessToken(), """
                                {"events":[{"eventId":"codex-local-usage:v1:%s",
                                 "occurredAt":"%s","totalTokens":900,
                                 "source":"TOKEN_USAGE_RECORD"}]}
                                """.formatted("c".repeat(64), Instant.now().minus(Duration.ofDays(8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.weeklyUsedTokens").value(230))
                .andExpect(jsonPath("$.teamQuota.weeklyUsedTokens").value(510));
        reportAndReadQuota(bobSession.accessToken(), """
                                {"events":[{"eventId":"codex-local-usage:v1:%s",
                                 "occurredAt":"%s","totalTokens":900,
                                 "source":"TOKEN_USAGE_RECORD"}]}
                                """.formatted("d".repeat(64), Instant.now().plus(Duration.ofMinutes(2))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.weeklyUsedTokens").value(230))
                .andExpect(jsonPath("$.teamQuota.weeklyUsedTokens").value(510));

        joinTeam(admin, alice.getId(), secondTeam);
        mvc.perform(get("/api/v1/admin/teams/{id}/quota-overview", firstTeam)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiveHourUsedTokens").value(510));
        mvc.perform(get("/api/v1/admin/teams/{id}/quota-overview", secondTeam)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiveHourUsedTokens").value(0));
        int aliceBindings = jdbc.queryForObject("select count(*) from session_bindings where user_id=?", Integer.class, alice.getId());
        org.assertj.core.api.Assertions.assertThat(aliceBindings).isPositive();
        mvc.perform(delete("/api/v1/admin/users/{id}", bob.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from session_bindings where user_id=?", Integer.class, alice.getId())).isEqualTo(aliceBindings);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("select count(*) from session_bindings where user_id=?", Integer.class, bob.getId())).isZero();
        mvc.perform(get("/api/v1/admin/teams/{id}/quota-overview", firstTeam)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiveHourUsedTokens").value(510));
    }

    @Test
    void adminResetsPersonalAndTeamUsageWithoutDeletingHistoryOrRebillingOldEvents() throws Exception {
        User admin = administrator();
        AuthResponse ordinary = authService.register("ordinary@example.com", PASSWORD, "Ordinary");
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(admin, "Reset team");
        setTeamLimits(admin, teamId, 500, 1_000);
        joinTeam(admin, member.getId(), teamId);
        mvc.perform(put("/api/v1/admin/users/{id}/token-limits", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiveHourTokenLimit\":300,\"weeklyTokenLimit\":600}"))
                .andExpect(status().isOk());
        AuthResponse memberSession = authService.login("member@example.com", PASSWORD);
        reportAndReadQuota(memberSession.accessToken(), usageRequest(EVENT_ID, 120))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(120))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(120));
        reportAndReadQuota(memberSession.accessToken(), """
                                {"events":[{"eventId":"codex-local-usage:v1:%s",
                                 "occurredAt":"%s","totalTokens":40,"source":"TOKEN_USAGE_RECORD"}]}
                                """.formatted("d".repeat(64), Instant.now().plusSeconds(120)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(120));

        mvc.perform(post("/api/v1/admin/users/{id}/quota-reset", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinary.accessToken())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/admin/teams/{id}/quota-reset", teamId))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/users/{id}/quota-reset", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/admin/teams/{id}/quota-reset", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/admin/users/{id}/quota-reset", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        Instant personalResetAt = users.findById(member.getId()).orElseThrow().getQuotaResetAt();
        org.assertj.core.api.Assertions.assertThat(events.sumTokensSince(member.getId(),
                personalResetAt, personalResetAt.plusSeconds(180), personalResetAt)).isZero();
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(0))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(300))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(120));

        String lateOldEvent = """
                {"events":[{"eventId":"codex-local-usage:v1:%s",
                 "occurredAt":"%s","totalTokens":50,"source":"TOKEN_USAGE_RECORD"}]}
                """.formatted("e".repeat(64), Instant.now().minusSeconds(60));
        reportAndReadQuota(memberSession.accessToken(), lateOldEvent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(0))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(170));

        mvc.perform(post("/api/v1/admin/teams/{id}/quota-reset", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(0))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(0))
                .andExpect(jsonPath("$.teamQuota.fiveHourRemainingTokens").value(500));
        reportAndReadQuota(memberSession.accessToken(), lateOldEvent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(0));
        org.assertj.core.api.Assertions.assertThat(events.count()).isEqualTo(3);

        reportAndReadQuota(memberSession.accessToken(), usageRequest("codex-local-usage:v1:" + "f".repeat(64), 20))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(20))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(20));
        org.assertj.core.api.Assertions.assertThat(events.count()).isEqualTo(4);
        mvc.perform(post("/api/v1/admin/teams/{id}/quota-reset", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(20))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(0));
    }

    @Test
    void missingTokenLimitsAreUnlimitedAndOldPersonalAssignmentRouteIsGone() throws Exception {
        User admin = administrator();
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(admin, "Operations");
        joinTeam(admin, member.getId(), teamId);

        AuthResponse memberSession = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.fiveHourTokenLimit").value(nullValue()))
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(0))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.blockReason").value(nullValue()));
        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codexAccountId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());

        setTeamLimits(admin, teamId, 500, 1_000);
        AuthResponse afterPolicy = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(afterPolicy.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true));
    }

    @Test
    void archivedTokenZeroDoesNotBlockUnconfiguredUsdWindows() throws Exception {
        User admin = administrator();
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        AuthResponse noTeamSession = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(noTeamSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.blockReason").value(nullValue()));

        UUID teamId = createTeam(admin, "Unlimited team");
        joinTeam(admin, member.getId(), teamId);
        AuthResponse session = authService.login("member@example.com", PASSWORD);
        reportAndReadQuota(session.accessToken(), usageRequest(EVENT_ID, 320))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(320))
                .andExpect(jsonPath("$.usage.weeklyUsedTokens").value(320))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.usage.weeklyRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.fiveHourTokenLimit").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.weeklyTokenLimit").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(320))
                .andExpect(jsonPath("$.teamQuota.weeklyUsedTokens").value(320))
                .andExpect(jsonPath("$.teamQuota.fiveHourRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.weeklyRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.blockReason").value(nullValue()));

        mvc.perform(put("/api/v1/admin/teams/{id}/token-limits", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiveHourTokenLimit\":null,\"weeklyTokenLimit\":0}"))
                .andExpect(status().isOk());
        AuthResponse cappedSession = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(cappedSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamQuota.fiveHourRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.weeklyRemainingTokens").value(0))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.blockReason").isEmpty());
    }

    @Test
    void absentFiveHourLimitDoesNotBlockAndTeamTotalIsIndependent() throws Exception {
        User admin = administrator();
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(admin, "Total team");
        joinTeam(admin, member.getId(), teamId);
        mvc.perform(put("/api/v1/admin/teams/{id}/token-limits", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fiveHourTokenLimit":null,"weeklyTokenLimit":1000,
                                 "totalTokenLimit":300}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokenLimit").value(300));
        AuthResponse session = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(session.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.policy.fiveHourTokenLimit").value(nullValue()))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.fiveHourRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.totalRemainingTokens").value(300));

        reportAndReadQuota(session.accessToken(), usageRequest(EVENT_ID, 320))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(320))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.totalUsedTokens").value(320))
                .andExpect(jsonPath("$.teamQuota.totalRemainingTokens").value(0))
                .andExpect(jsonPath("$.blockReason").isEmpty());
    }

    @Test
    void changingTeamTokenLimitsImmediatelyInvalidatesMemberSessions() throws Exception {
        User admin = administrator();
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(admin, "Limited team");
        setTeamLimits(admin, teamId, 500, 1_000);
        joinTeam(admin, member.getId(), teamId);
        AuthResponse oldSession = authService.login("member@example.com", PASSWORD);

        setTeamLimits(admin, teamId, 200, 400);

        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(oldSession.accessToken())))
                .andExpect(status().isUnauthorized());
        AuthResponse newSession = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(OVERVIEW).with(boundRequest())
                        .header(HttpHeaders.AUTHORIZATION, bearer(newSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.fiveHourTokenLimit").value(nullValue()))
                .andExpect(jsonPath("$.teamQuota.fiveHourTokenLimit").value(200))
                .andExpect(jsonPath("$.teamQuota.weeklyTokenLimit").value(400));
    }

    @Test
    void deletingATeamClearsMembershipAndInvalidatesMembers() throws Exception {
        User admin = administrator();
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(admin, "Temporary team");
        joinTeam(admin, member.getId(), teamId);
        AuthResponse assignedSession = authService.login("member@example.com", PASSWORD);

        mvc.perform(delete("/api/v1/admin/teams/{id}", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(assignedSession.accessToken())))
                .andExpect(status().isUnauthorized());
        AuthResponse afterDeletion = authService.login("member@example.com", PASSWORD);
        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(afterDeletion.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(nullValue()));
    }

    private User administrator() {
        authService.register("admin@example.com", PASSWORD, "Administrator");
        User admin = users.findByEmail("admin@example.com").orElseThrow();
        admin.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        return users.saveAndFlush(admin);
    }

    private UUID createTeam(User admin, String name) throws Exception {
        String location = mvc.perform(post("/api/v1/admin/teams")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(teamRequest(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private void setTeamLimits(User admin, UUID teamId, long fiveHour, long weekly) throws Exception {
        mvc.perform(put("/api/v1/admin/teams/{id}/token-limits", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiveHourTokenLimit\":%d,\"weeklyTokenLimit\":%d}"
                                .formatted(fiveHour, weekly)))
                .andExpect(status().isOk());
    }

    private void joinTeam(User admin, UUID userId, UUID teamId) throws Exception {
        mvc.perform(put("/api/v1/admin/users/{id}/quota-policy", userId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s","fiveHourQuotaPercent":null,
                                 "weeklyQuotaPercent":null}
                                """.formatted(teamId)))
                .andExpect(status().isOk());
    }

    private String usageRequest(String eventId, long tokens) {
        return """
                {"events":[{"eventId":"%s",
                 "occurredAt":"%s","totalTokens":%d,"source":"TOKEN_USAGE_RECORD"}]}
                """.formatted(eventId, Instant.now().toString(), tokens);
    }

    private String teamRequest(String name) {
        return """
                {"name":"%s","description":"Managed policy team","status":"ACTIVE",
                 "fiveHourQuotaPercent":90,"weeklyQuotaPercent":90}
                """.formatted(name);
    }

    private String bearer(User user) {
        return bearer(jwtService.create(user.getId(), user.getRole().name(), user.getSessionVersion()));
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }

    private RequestPostProcessor boundRequest() {
        return request -> ClientBindingFixture.forUser(jdbc,
                jwtService.parseUserId(request.getHeader(HttpHeaders.AUTHORIZATION).substring(7)))
                .postProcessRequest(request);
    }

    private ResultActions reportAndReadQuota(String accessToken, String body) throws Exception {
        mvc.perform(post("/api/v1/client/usage/events")
                        .header(HttpHeaders.AUTHORIZATION, bearer(accessToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedEvents").value(1));
        return mvc.perform(get(OVERVIEW).with(boundRequest())
                .header(HttpHeaders.AUTHORIZATION, bearer(accessToken)));
    }
}
