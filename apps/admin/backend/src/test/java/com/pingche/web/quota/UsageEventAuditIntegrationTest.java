package com.pingche.web.quota;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthResponse;
import com.pingche.web.auth.AuthService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageEventAuditIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired CodexUsageEventRepository events;

    @Test
    void optionalMetadataSurvivesIngestionAndLegacyClientsKeepTheirOriginalResponseShape() throws Exception {
        AuthResponse session = auth.register("meta-" + UUID.randomUUID() + "@example.com",
                "test-password-with-sufficient-length", "Metadata");
        String id = "codex-local-usage:v1:" + UUID.randomUUID().toString().replace("-", "").repeat(2);
        String body = """
                {"events":[{"eventId":"%s","occurredAt":"%s","totalTokens":42,"source":"TOKEN_USAGE_RECORD",
                "model":"gpt-6.1-sol","reasoningEffort":"max"}]}
                """.formatted(id, Instant.now());
        mvc.perform(post("/api/v1/client/usage/events").contentType("application/json").content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/usage/events?includeMetadata=true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].model").value("gpt-6.1-sol"))
                .andExpect(jsonPath("$.items[0].reasoningEffort").value("max"))
                .andExpect(jsonPath("$.items[0].totalTokens").value(42));
        mvc.perform(get("/api/v1/client/usage/events")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].model").doesNotExist())
                .andExpect(jsonPath("$.items[0].reasoningEffort").doesNotExist())
                .andExpect(jsonPath("$.items[0].totalTokens").value(42));
        // Retrying exactly the same event remains idempotent.
        mvc.perform(post("/api/v1/client/usage/events").contentType("application/json").content(body)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/usage/events?includeMetadata=true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void memberCanPageOnlyTheirOwnLedgerAndCannotSelectAnotherUser() throws Exception {
        String email = "self-ledger-" + UUID.randomUUID() + "@example.com";
        AuthResponse session = auth.register(email, "test-password-with-sufficient-length", "Self");
        UUID self = users.findByEmail(email).orElseThrow().getId();
        String otherEmail = "other-ledger-" + UUID.randomUUID() + "@example.com";
        auth.register(otherEmail, "test-password-with-sufficient-length", "Other");
        UUID other = users.findByEmail(otherEmail).orElseThrow().getId();
        Instant now = Instant.now();
        events.saveAllAndFlush(List.of(
                new CodexUsageEvent(UUID.randomUUID().toString(), self, 123,
                        now.minusSeconds(20), now.minusSeconds(10), "TOKEN_USAGE_RECORD"),
                new CodexUsageEvent(UUID.randomUUID().toString(), self, 456,
                        now.minusSeconds(5), now, "CUMULATIVE_TOKEN_COUNT_FALLBACK"),
                new CodexUsageEvent(UUID.randomUUID().toString(), other, 999,
                        now.minusSeconds(5), now, "TOKEN_USAGE_RECORD")));

        mvc.perform(get("/api/v1/client/usage/events?page=0&size=1&userId=" + other)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].totalTokens").value(456))
                .andExpect(jsonPath("$.items[0].source").value("CUMULATIVE_TOKEN_COUNT_FALLBACK"))
                .andExpect(jsonPath("$.items[0].accessToken").doesNotExist());
        mvc.perform(get("/api/v1/client/usage/events?page=1&size=1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].totalTokens").value(123));
        for (String query : List.of("page=-1", "size=0", "size=101")) {
            mvc.perform(get("/api/v1/client/usage/events?" + query)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/v1/client/usage/events")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanPageOnlyOneUsersAcceptedUsageEventsWithoutRawCredentials() throws Exception {
        String targetEmail = "audit-target-" + UUID.randomUUID() + "@example.com";
        AuthResponse member = auth.register(targetEmail,
                "test-password-with-sufficient-length", "Target");
        UUID targetId = users.findByEmail(targetEmail).orElseThrow().getId();
        String otherEmail = "audit-other-" + UUID.randomUUID() + "@example.com";
        auth.register(otherEmail, "test-password-with-sufficient-length", "Other");
        UUID otherId = users.findByEmail(otherEmail).orElseThrow().getId();
        String adminEmail = "audit-admin-" + UUID.randomUUID() + "@example.com";
        auth.register(adminEmail, "test-password-with-sufficient-length", "Admin");
        User admin = users.findByEmail(adminEmail).orElseThrow();
        admin.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        users.saveAndFlush(admin);
        AuthResponse adminSession = auth.login(adminEmail, "test-password-with-sufficient-length");

        Instant now = Instant.now();
        events.saveAllAndFlush(List.of(
                new CodexUsageEvent("a".repeat(64), targetId, 10,
                        now.minusSeconds(30), now.minusSeconds(20), "TOKEN_USAGE_RECORD"),
                new CodexUsageEvent("b".repeat(64), targetId, 20,
                        now.minusSeconds(10), now.minusSeconds(5), "TOKEN_USAGE_RECORD"),
                new CodexUsageEvent("c".repeat(64), otherId, 999,
                        now.minusSeconds(10), now.minusSeconds(5), "TOKEN_USAGE_RECORD")));

        mvc.perform(get("/api/v1/admin/users/{id}/usage-events?page=0&size=1", targetId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users/{id}/usage-events?page=0&size=1", targetId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminSession.accessToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].totalTokens").value(20))
                .andExpect(jsonPath("$.items[0].source").value("TOKEN_USAGE_RECORD"))
                .andExpect(jsonPath("$.items[0].receivedAt").exists())
                .andExpect(jsonPath("$.items[0].deviceToken").doesNotExist())
                .andExpect(jsonPath("$.items[0].rawSession").doesNotExist());
        mvc.perform(get("/api/v1/admin/users/{id}/usage-events?page=1&size=1", targetId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminSession.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].totalTokens").value(10));
    }

    @Test
    void rejectsUnknownUsersAndOutOfRangePagination() throws Exception {
        String adminEmail = "audit-admin-" + UUID.randomUUID() + "@example.com";
        auth.register(adminEmail, "test-password-with-sufficient-length", "Admin");
        User admin = users.findByEmail(adminEmail).orElseThrow();
        admin.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        users.saveAndFlush(admin);
        AuthResponse session = auth.login(adminEmail, "test-password-with-sufficient-length");

        mvc.perform(get("/api/v1/admin/users/{id}/usage-events", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/users/{id}/usage-events?page=-1", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/users/{id}/usage-events?size=101", admin.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isBadRequest());
    }
}
