package com.pingche.web.quota;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthResponse;
import com.pingche.web.auth.AuthService;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.sessionbinding.ClientBindingFixture;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Instant;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfficialAccountIntegrationTest {
    private static final String PASSWORD = "test-password-with-sufficient-length";

    @Autowired private MockMvc mvc;
    @Autowired private AuthService authService;
    @Autowired private JwtService jwtService;
    @Autowired private RefreshSessionRepository sessions;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private OfficialAccountRepository accounts;
    @Autowired private OfficialQuotaSnapshotRepository snapshots;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        ClientBindingFixture.clearBindings(jdbc);
        teams.deleteAll();
        accounts.deleteAll();
        snapshots.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
    }

    @AfterEach
    void clearBindingPrerequisites() { ClientBindingFixture.clearBindings(jdbc); }

    @Test
    void adminRegistersReadOnlyAccountAndLinkedTeamTracksItsEstimate() throws Exception {
        authService.register("admin@example.com", PASSWORD, "Administrator");
        User admin = users.findByEmail("admin@example.com").orElseThrow();
        admin.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        admin = users.saveAndFlush(admin);
        AuthResponse reporter = authService.register("reporter@example.com", PASSWORD, "Reporter");
        String adminToken = jwtService.create(admin.getId(), admin.getRole().name(), admin.getSessionVersion());

        mvc.perform(get("/api/v1/admin/official-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(reporter.accessToken())))
                .andExpect(status().isForbidden());

        String created = mvc.perform(post("/api/v1/admin/official-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"研发账户","estimatedTotalTokens":1000,
                                 "reporterEmail":"reporter@example.com"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.quota").value(org.hamcrest.Matchers.nullValue()))
                .andReturn().getResponse().getContentAsString();
        UUID accountId = UUID.fromString(mapper.readTree(created).path("id").asString());
        long version = mapper.readTree(created).path("version").asLong();

        mvc.perform(post("/api/v1/admin/official-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"重复来源","estimatedTotalTokens":1000,
                                 "reporterEmail":"reporter@example.com"}
                                """))
                .andExpect(status().isConflict());

        mvc.perform(put("/api/v1/client/official-quota")
                        .header("X-Quota-Guard-Device-ID", ClientBindingFixture.bind(jdbc, reporter.user().id(), accountId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(reporter.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planType":"plus","fetchedAt":"%s",
                                 "windows":[{"limitId":"codex","slot":"secondary",
                                 "windowDurationMins":10080,"usedPercent":40}]}
                                """.formatted(Instant.now())))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/official-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].quota.windows[0].usedPercent").value(40));

        String teamLocation = mvc.perform(post("/api/v1/admin/teams")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"研发团队","description":"","status":"ACTIVE",
                                 "fiveHourQuotaPercent":100,"weeklyQuotaPercent":100}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getHeader(HttpHeaders.LOCATION);
        UUID teamId = UUID.fromString(teamLocation.substring(teamLocation.lastIndexOf('/') + 1));
        mvc.perform(put("/api/v1/admin/teams/{id}/token-limits", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"weeklyTokenLimit":2000,"officialAccountId":"%s",
                                 "totalTokenLimit":999}
                                """.formatted(accountId)))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/teams/{id}/token-limits", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fiveHourTokenLimit":null,"weeklyTokenLimit":2000,
                                 "officialAccountId":"%s","totalTokenLimit":1000}
                                """.formatted(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokenLimit").value(1000));

        mvc.perform(put("/api/v1/admin/official-accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"label":"研发账户","estimatedTotalTokens":500,
                                 "reporterEmail":"reporter@example.com","version":%d}
                                """.formatted(version)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/teams/{id}/quota-overview", teamId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalTokenLimit").value(500));
        mvc.perform(delete("/api/v1/admin/official-accounts/{id}", accountId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken)))
                .andExpect(status().isConflict());
    }

    private String bearer(String token) { return "Bearer " + token; }
}
