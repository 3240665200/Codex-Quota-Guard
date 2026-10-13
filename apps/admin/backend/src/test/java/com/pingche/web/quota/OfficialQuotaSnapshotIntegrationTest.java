package com.pingche.web.quota;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthResponse;
import com.pingche.web.auth.AuthService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
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
class OfficialQuotaSnapshotIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired Clock clock;

    @Test
    void clientReportsSafeOfficialQuotaAndOnlyAdminReadsAnotherUsersSnapshot() throws Exception {
        AuthResponse member = auth.register("official-snapshot-member@example.com",
                "test-password-with-sufficient-length", "Member");
        User memberUser = users.findByEmail("official-snapshot-member@example.com").orElseThrow();
        AuthResponse adminSession = auth.register("official-snapshot-admin@example.com",
                "test-password-with-sufficient-length", "Admin");
        User admin = users.findByEmail("official-snapshot-admin@example.com").orElseThrow();
        admin.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        users.saveAndFlush(admin);
        adminSession = auth.login("official-snapshot-admin@example.com",
                "test-password-with-sufficient-length");

        Instant fetchedAt = clock.instant();
        mvc.perform(put("/api/v1/client/official-quota")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"planType":"plus","fetchedAt":"%s",
                                "windows":[{"limitId":"codex","slot":"primary",
                                "windowDurationMins":300,"usedPercent":25.5,
                                "resetsAt":"%s"}]}
                                """.formatted(fetchedAt, fetchedAt.plus(Duration.ofHours(5)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planType").value("plus"));
        mvc.perform(get("/api/v1/admin/users/{id}/official-quota", memberUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/users/{id}/official-quota", memberUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminSession.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windows[0].usedPercent").value(25.5));
    }
}
