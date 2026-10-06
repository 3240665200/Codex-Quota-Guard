package com.pingche.web.quota;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthResponse;
import com.pingche.web.auth.AuthService;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.codex.CodexAccountRepository;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaPolicyIntegrationTest {

    private static final String PASSWORD = "test-password-with-sufficient-length";
    private static final String CLIENT_QUOTA_OVERVIEW = "/api/v1/client/quota/overview";

    @Autowired private MockMvc mvc;
    @Autowired private AuthService authService;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;
    @Autowired private RefreshSessionRepository refreshSessions;
    @Autowired private CodexAccountRepository accounts;
    @Autowired private TeamRepository teams;
    @Autowired private UserQuotaUsageRepository usage;
    @Autowired private QuotaUsageService quotaUsage;

    @BeforeEach
    void cleanDatabase() {
        usage.deleteAll();
        refreshSessions.deleteAll();
        users.deleteAll();
        teams.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void administratorEndpointsRequireAdminAndCorsAllowsPut() throws Exception {
        AuthResponse ordinarySession = authService.register(
                "ordinary@example.com", PASSWORD, "Ordinary"
        );
        User ordinary = users.findByEmail("ordinary@example.com").orElseThrow();

        mvc.perform(post("/api/v1/admin/teams")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinarySession.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(teamRequest("Ordinary team")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mvc.perform(put("/api/v1/admin/users/{id}/quota-policy", ordinary.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinarySession.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mvc.perform(options("/api/v1/admin/users/{id}/quota-policy", ordinary.getId())
                        .header(HttpHeaders.ORIGIN, "http://localhost:1420")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT"))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        containsString("PUT")
                ));
    }

    @Test
    void quotaPolicyAndTeamPolicyChangesInvalidateAffectedSessions() throws Exception {
        User administrator = administrator();
        AuthResponse memberSession = authService.register(
                "member@example.com", PASSWORD, "Member"
        );
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(administrator, "Engineering");

        mvc.perform(put("/api/v1/admin/users/{id}/quota-policy", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s","fiveHourQuotaPercent":null,
                                 "weeklyQuotaPercent":80}
                                """.formatted(teamId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.teamId").value(teamId.toString()))
                .andExpect(jsonPath("$.fiveHourQuotaPercent").value(nullValue()))
                .andExpect(jsonPath("$.weeklyQuotaPercent").value(80));

        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        AuthResponse afterPolicyChange = authService.login("member@example.com", PASSWORD);
        mvc.perform(patch("/api/v1/admin/teams/{id}", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fiveHourQuotaPercent\":70,\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fiveHourQuotaPercent").value(70))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(afterPolicyChange.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
    }

    @Test
    void overviewUsesOnlyTheCallerAndFailsClosedForUnknownUsage() throws Exception {
        User administrator = administrator();
        AuthResponse aliceSession = authService.register("alice@example.com", PASSWORD, "Alice");
        AuthResponse bobSession = authService.register("bob@example.com", PASSWORD, "Bob");
        User alice = users.findByEmail("alice@example.com").orElseThrow();
        User bob = users.findByEmail("bob@example.com").orElseThrow();
        UUID accountId = createAccount(administrator, "quota-account@example.com", "ACTIVE");

        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codexAccountId\":\"%s\"}".formatted(accountId)))
                .andExpect(status().isOk());
        AuthResponse currentAlice = authService.login("alice@example.com", PASSWORD);

        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(currentAlice.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(alice.getId().toString()))
                .andExpect(jsonPath("$.user.email").value("alice@example.com"))
                .andExpect(jsonPath("$.usage.measurementStatus").value("NOT_MEASURED"))
                .andExpect(jsonPath("$.usage.fiveHourUsedPercent").value(nullValue()))
                .andExpect(jsonPath("$.usage.weeklyUsedPercent").value(nullValue()))
                .andExpect(jsonPath("$.usage.fiveHourRemainingPercent").value(nullValue()))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockReason").value("USAGE_NOT_MEASURED"));

        quotaUsage.recordMeasuredUsage(
                alice.getId(),
                accountId,
                new QuotaUsageMeasurement(
                        new BigDecimal("30.00"),
                        new BigDecimal("20.00"),
                        Instant.now().plus(1, ChronoUnit.HOURS),
                        Instant.now().plus(1, ChronoUnit.DAYS)
                )
        );
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(currentAlice.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.measurementStatus").value("CURRENT"))
                .andExpect(jsonPath("$.usage.fiveHourUsedPercent").value(30.0))
                .andExpect(jsonPath("$.usage.fiveHourRemainingPercent").value(70.0))
                .andExpect(jsonPath("$.policy.fiveHourLimitPercent").value(100))
                .andExpect(jsonPath("$.policy.weeklyLimitPercent").value(100))
                .andExpect(jsonPath("$.policy.fiveHourSource").value("DEFAULT"))
                .andExpect(jsonPath("$.policy.weeklySource").value("DEFAULT"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.blockReason").value(nullValue()));

        UUID teamId = createTeam(administrator, "Alice team");
        mvc.perform(put("/api/v1/admin/users/{id}/quota-policy", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s","fiveHourQuotaPercent":0,
                                 "weeklyQuotaPercent":null}
                                """.formatted(teamId)))
                .andExpect(status().isOk());
        AuthResponse zeroLimitSession = authService.login("alice@example.com", PASSWORD);
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(zeroLimitSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.fiveHourLimitPercent").value(0))
                .andExpect(jsonPath("$.policy.weeklyLimitPercent").value(90))
                .andExpect(jsonPath("$.policy.fiveHourSource").value("USER"))
                .andExpect(jsonPath("$.policy.weeklySource").value("TEAM"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockReason").value("FIVE_HOUR_QUOTA_EXCEEDED"));

        mvc.perform(put("/api/v1/admin/users/{id}/quota-policy", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":null,"fiveHourQuotaPercent":null,
                                 "weeklyQuotaPercent":null}
                                """))
                .andExpect(status().isOk());
        AuthResponse defaultPolicySession = authService.login("alice@example.com", PASSWORD);
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(defaultPolicySession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policy.fiveHourSource").value("DEFAULT"))
                .andExpect(jsonPath("$.policy.weeklySource").value("DEFAULT"))
                .andExpect(jsonPath("$.allowed").value(true));

        UUID replacementAccountId = createAccount(
                administrator, "replacement-account@example.com", "ACTIVE"
        );
        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codexAccountId\":\"%s\"}".formatted(replacementAccountId)))
                .andExpect(status().isOk());
        AuthResponse afterReassignment = authService.login("alice@example.com", PASSWORD);

        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(afterReassignment.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.measurementStatus").value("NOT_MEASURED"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockReason").value("USAGE_NOT_MEASURED"));

        // The overview URL contains no user id. Each caller only receives their
        // own data; an old session cannot be reused after a policy change.
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(bobSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(bob.getId().toString()));
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(aliceSession.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overviewRejectsUnassignedAndUnavailableAccountOrTeamAndExpiresMeasurements()
            throws Exception {
        User administrator = administrator();
        AuthResponse memberSession = authService.register(
                "member@example.com", PASSWORD, "Member"
        );
        User member = users.findByEmail("member@example.com").orElseThrow();

        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(false))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockReason").value("CODEX_ACCOUNT_UNASSIGNED"));

        UUID teamId = createTeam(administrator, "Operations");
        UUID accountId = createAccount(administrator, "operations-account@example.com", "ACTIVE");
        mvc.perform(put("/api/v1/admin/users/{id}/quota-policy", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s","fiveHourQuotaPercent":null,
                                 "weeklyQuotaPercent":null}
                                """.formatted(teamId)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codexAccountId\":\"%s\"}".formatted(accountId)))
                .andExpect(status().isOk());
        AuthResponse assignedSession = authService.login("member@example.com", PASSWORD);

        usage.saveAndFlush(new UserQuotaUsage(
                member.getId(),
                accountId,
                new BigDecimal("10.00"),
                new BigDecimal("10.00"),
                Instant.now().plus(1, ChronoUnit.HOURS),
                Instant.now().plus(1, ChronoUnit.DAYS),
                Instant.now().minus(6, ChronoUnit.MINUTES)
        ));
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(assignedSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.measurementStatus").value("NOT_MEASURED"))
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockReason").value("USAGE_NOT_MEASURED"));

        mvc.perform(patch("/api/v1/admin/codex-accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(assignedSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockReason").value("CODEX_ACCOUNT_UNAVAILABLE"));

        mvc.perform(patch("/api/v1/admin/codex-accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/v1/admin/teams/{id}", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"version\":0}"))
                .andExpect(status().isOk());
        AuthResponse afterTeamDisabled = authService.login("member@example.com", PASSWORD);
        mvc.perform(get(CLIENT_QUOTA_OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(afterTeamDisabled.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.blockReason").value("TEAM_UNAVAILABLE"));
    }

    @Test
    void deletingATeamClearsMembershipAndInvalidatesMembers() throws Exception {
        User administrator = administrator();
        authService.register("member@example.com", PASSWORD, "Member");
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID teamId = createTeam(administrator, "Temporary team");
        mvc.perform(put("/api/v1/admin/users/{id}/quota-policy", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"teamId":"%s","fiveHourQuotaPercent":null,
                                 "weeklyQuotaPercent":null}
                                """.formatted(teamId)))
                .andExpect(status().isOk());
        AuthResponse assignedSession = authService.login("member@example.com", PASSWORD);

        mvc.perform(delete("/api/v1/admin/teams/{id}", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
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
        User administrator = users.findByEmail("admin@example.com").orElseThrow();
        administrator.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        return users.saveAndFlush(administrator);
    }

    private UUID createTeam(User administrator, String name) throws Exception {
        String location = mvc.perform(post("/api/v1/admin/teams")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(teamRequest(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String teamRequest(String name) {
        return """
                {"name":"%s","description":"Managed policy team","status":"ACTIVE",
                 "fiveHourQuotaPercent":90,"weeklyQuotaPercent":90}
                """.formatted(name);
    }

    private UUID createAccount(User administrator, String email, String accountStatus) throws Exception {
        String location = mvc.perform(post("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","displayName":"Managed quota account",
                                 "status":"%s","credential":{"access_token":"test-token"}}
                                """.formatted(email, accountStatus)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String bearer(User user) {
        return bearer(jwtService.create(
                user.getId(), user.getRole().name(), user.getSessionVersion()
        ));
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
