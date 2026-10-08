package com.pingche.web.quota;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
