package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verifyNoInteractions;
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
        confirm(admin, plan, true).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGOUT_CONTEXT_CHANGED"));
        assertThat(org.mockito.Mockito.mockingDetails(client).getInvocations()).allMatch(invocation -> invocation.getMethod().getName().equals("fetch"));
    }

    private JsonNode preview(String mode) throws Exception {
        String body = mvc.perform(post(url("preview")).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("mode", mode))))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(User owner, String planId, boolean acknowledge) throws Exception {
        return mvc.perform(post(url("confirm")).secure(true).header(HttpHeaders.AUTHORIZATION, token(owner))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("planId", planId, "acknowledgeLogoutRisk", acknowledge))));
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
