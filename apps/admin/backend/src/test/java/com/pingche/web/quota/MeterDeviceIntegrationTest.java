package com.pingche.web.quota;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthResponse;
import com.pingche.web.auth.AuthService;
import com.pingche.web.meter.MeterDeviceService;
import com.pingche.web.meter.MeterDeviceRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MeterDeviceIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired MeterDeviceService devices;
    @Autowired MeterDeviceRepository meterRepository;
    @Autowired QuotaOverviewService overview;

    @Test
    void expiredDeviceRenewsIndependentlyButRevokedOrDisabledDevicesCannotRenew() throws Exception {
        String email = "meter-renew-" + UUID.randomUUID() + "@example.com";
        AuthResponse session = auth.register(email, "test-password-with-sufficient-length", "Meter renew");
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        UUID deviceId = UUID.randomUUID();
        var pairing = devices.enroll(userId, deviceId);
        var stored = meterRepository.findById(deviceId).orElseThrow();
        stored.rotate(stored.getTokenHash(), Instant.now().minusSeconds(1));
        meterRepository.saveAndFlush(stored);
        // A closed/revoked desktop session must not remove the independent device grant.
        auth.logout(userId, session.refreshToken());
        mvc.perform(post("/api/v1/meter/heartbeat")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/meter/credential/renew")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.expiresAt").isString())
                .andExpect(jsonPath("$.deviceToken").doesNotExist());
        mvc.perform(post("/api/v1/meter/heartbeat")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isOk());
        assertThat(meterRepository.findById(deviceId).orElseThrow().getExpiresAt())
                .isAfter(Instant.now().plusSeconds(29 * 86400));
        User user = users.findById(userId).orElseThrow();
        user.updateAdministration(User.Status.DISABLED, user.getRole());
        users.saveAndFlush(user);
        mvc.perform(post("/api/v1/meter/credential/renew")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isUnauthorized());
        user.updateAdministration(User.Status.ACTIVE, user.getRole());
        users.saveAndFlush(user);
        devices.revoke(userId, deviceId);
        mvc.perform(post("/api/v1/meter/credential/renew")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void renewalGraceIsBoundedAndCannotBypassTheExactMethodOrPath() throws Exception {
        String email = "meter-grace-" + UUID.randomUUID() + "@example.com";
        auth.register(email, "test-password-with-sufficient-length", "Grace");
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        UUID deviceId = UUID.randomUUID();
        var pairing = devices.enroll(userId, deviceId);
        var stored = meterRepository.findById(deviceId).orElseThrow();
        stored.rotate(stored.getTokenHash(), Instant.now().minusSeconds(31 * 86400));
        meterRepository.saveAndFlush(stored);
        mvc.perform(post("/api/v1/meter/credential/renew")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isUnauthorized());
        stored = meterRepository.findById(deviceId).orElseThrow();
        stored.rotate(stored.getTokenHash(), Instant.now().minusSeconds(1));
        meterRepository.saveAndFlush(stored);
        mvc.perform(get("/api/v1/meter/credential/renew")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/meter/credential/renew/")
                        .header("X-Quota-Guard-Meter", pairing.deviceToken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void reusedInstallationIdReturnsItsNewAuthenticatedOwner() throws Exception {
        String email = "meter-reused-" + UUID.randomUUID() + "@example.com";
        AuthResponse original = auth.register(email, "test-password-with-sufficient-length", "Original");
        UUID oldOwner = users.findByEmail(email).orElseThrow().getId();
        UUID deviceId = UUID.randomUUID();
        var previous = devices.enroll(oldOwner, deviceId);
        meterRepository.deleteById(deviceId);
        meterRepository.flush();
        String nextEmail = "meter-recreated-" + UUID.randomUUID() + "@example.com";
        AuthResponse next = auth.register(nextEmail, "test-password-with-sufficient-length", "Recreated");
        UUID nextOwner = users.findByEmail(nextEmail).orElseThrow().getId();
        mvc.perform(post("/api/v1/client/meter/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + next.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\"}".formatted(deviceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(nextOwner.toString()));
        mvc.perform(post("/api/v1/meter/credential/renew")
                        .header("X-Quota-Guard-Meter", previous.deviceToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/client/meter/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + original.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\"}".formatted(deviceId)))
                .andExpect(status().isConflict());
    }

    @Test
    void pairedMeterCanHeartbeatAndReportUsageWithoutAnOpenDesktopSession() throws Exception {
        String email = "meter-" + UUID.randomUUID() + "@example.com";
        AuthResponse session = auth.register(email, "test-password-with-sufficient-length", "Meter user");
        UUID deviceId = UUID.randomUUID();

        String body = mvc.perform(post("/api/v1/client/meter/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\"}".formatted(deviceId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.deviceToken").isString())
                .andReturn().getResponse().getContentAsString();
        String deviceToken = json.readTree(body).path("deviceToken").asString();

        mvc.perform(get("/api/v1/users/me")
                        .header("X-Quota-Guard-Meter", deviceToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/dashboard")
                        .header("X-Quota-Guard-Meter", deviceToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/meter/heartbeat"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/meter/heartbeat")
                        .header("X-Quota-Guard-Meter", deviceToken)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/meter/heartbeat")
                        .header("X-Quota-Guard-Meter", deviceToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/meter/usage/events")
                        .header("X-Quota-Guard-Meter", deviceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"events":[{"eventId":"codex-local-usage:v1:%s",
                                "occurredAt":"%s","totalTokens":120,
                                "source":"TOKEN_USAGE_RECORD"}]}
                                """.formatted("a".repeat(64), Instant.now())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedEvents").value(1))
                .andExpect(jsonPath("$.usage").doesNotExist());
        mvc.perform(get("/api/v1/client/quota/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        assertThat(overview.overview(users.findByEmail(email).orElseThrow().getId())
                .usage().fiveHourUsedTokens()).isEqualTo(120);
    }

    @Test
    void administratorCanSeeDeviceLivenessWithoutReadingItsToken() throws Exception {
        String memberEmail = "meter-member-" + UUID.randomUUID() + "@example.com";
        AuthResponse member = auth.register(memberEmail,
                "test-password-with-sufficient-length", "Member");
        User memberUser = users.findByEmail(memberEmail).orElseThrow();
        UUID deviceId = UUID.randomUUID();
        String enrollment = mvc.perform(post("/api/v1/client/meter/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"%s\"}".formatted(deviceId)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(enrollment).path("deviceToken").asString();
        mvc.perform(post("/api/v1/meter/heartbeat")
                        .header("X-Quota-Guard-Meter", token))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/admin/users/{id}/meter-devices", memberUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + member.accessToken()))
                .andExpect(status().isForbidden());

        String adminEmail = "meter-admin-" + UUID.randomUUID() + "@example.com";
        auth.register(adminEmail, "test-password-with-sufficient-length", "Admin");
        User admin = users.findByEmail(adminEmail).orElseThrow();
        admin.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        users.saveAndFlush(admin);
        AuthResponse adminSession = auth.login(adminEmail, "test-password-with-sufficient-length");
        mvc.perform(get("/api/v1/admin/users/{id}/meter-devices", memberUser.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminSession.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$[0].online").value(true))
                .andExpect(jsonPath("$[0].deviceToken").doesNotExist());

        int deletionStatus = mvc.perform(delete(
                        "/api/v1/admin/users/{id}/meter-devices/{deviceId}",
                        memberUser.getId(), deviceId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminSession.accessToken()))
                .andReturn().getResponse().getStatus();
        assertThat(deletionStatus).isEqualTo(204);
        int oldTokenStatus = mvc.perform(post("/api/v1/meter/heartbeat")
                        .header("X-Quota-Guard-Meter", token))
                .andReturn().getResponse().getStatus();
        assertThat(oldTokenStatus).isEqualTo(401);
    }

    @Test
    void revokedDeviceCannotPairItselfAgainOrSwitchToAnotherSystemUser() throws Exception {
        String originalEmail = "meter-bound-" + UUID.randomUUID() + "@example.com";
        AuthResponse original = auth.register(originalEmail,
                "test-password-with-sufficient-length", "Original");
        AuthResponse other = auth.register("meter-other-" + UUID.randomUUID() + "@example.com",
                "test-password-with-sufficient-length", "Other");
        UUID originalId = users.findByEmail(originalEmail).orElseThrow().getId();
        UUID deviceId = UUID.randomUUID();
        String request = "{\"deviceId\":\"%s\"}".formatted(deviceId);

        mvc.perform(post("/api/v1/client/meter/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + original.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/client/meter/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + other.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());

        devices.revoke(originalId, deviceId);
        mvc.perform(post("/api/v1/client/meter/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + original.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isConflict());
    }
}
