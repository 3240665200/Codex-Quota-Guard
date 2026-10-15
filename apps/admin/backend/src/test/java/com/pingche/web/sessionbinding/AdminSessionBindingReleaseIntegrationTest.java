package com.pingche.web.sessionbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.meter.MeterDevice;
import com.pingche.web.meter.MeterDeviceRepository;
import com.pingche.web.officialsession.OfficialAccountInfo;
import com.pingche.web.officialsession.OfficialConnectionRefreshResponse;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.officialsession.OfficialSessionClient;
import com.pingche.web.officialsession.OfficialSessionInfo;
import com.pingche.web.officialsession.OfficialSessions;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-session-binding-release;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "MANAGEMENT_ALLOW_LOOPBACK_HTTP=true"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminSessionBindingReleaseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
    private static final String RELEASE = "/api/v1/admin/session-bindings/{id}/release";
    private static final String DEVICE_HEADER = "X-Quota-Guard-Device-ID";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired SessionBindingService service;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired OfficialAccountRepository accounts;
    @Autowired MeterDeviceRepository devices;
    @MockitoBean Clock clock;
    @MockitoBean OfficialConnectionService official;
    @MockitoBean OfficialSessionClient officialSessions;
    private User user;
    private User admin;
    private UUID deviceId;
    private UUID accountId;
    private UUID revision;
    private UUID attemptId;
    private UUID bindingId;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        accountId = accounts.saveAndFlush(new OfficialAccount("Release " + UUID.randomUUID(), 1000, null, NOW)).getId();
        Team team = new Team("Release " + UUID.randomUUID(), "", Team.Status.ACTIVE, 100, 100, NOW);
        team.updateTokenLimits(null, null, null, accountId, NOW);
        team = teams.saveAndFlush(team);
        user = new User(UUID.randomUUID() + "@example.com", "synthetic-unused-hash", "Bound user");
        user.updateQuotaPolicy(team.getId(), null, null, NOW);
        user = users.saveAndFlush(user);
        admin = users.saveAndFlush(User.bootstrapAdministrator(UUID.randomUUID() + "@example.com", "unused", "Admin"));
        deviceId = UUID.randomUUID();
        devices.saveAndFlush(new MeterDevice(deviceId, user.getId(), UUID.randomUUID().toString().replace("-", "").repeat(2), NOW, NOW.plusSeconds(3600)));
        revision = UUID.randomUUID();
        jdbc.update("insert into official_account_connections (official_account_id,requested_account_id,authorization_cipher,bound_account_id,bound_user_id,status,revision,imported_at,version) values (?,?,?,?,?,?,?,?,0)",
                accountId, "account-" + accountId, "synthetic-no-secret", "account-" + accountId, "official-user", "CONNECTED", revision, NOW);
        attemptId = UUID.randomUUID();
        bindingId = UUID.randomUUID();
        jdbc.update("insert into session_binding_attempts (id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                attemptId, user.getId(), deviceId, deviceId, team.getId(), team.getVersion(), accountId, revision, "BOUND", NOW, NOW.plusSeconds(1800));
        jdbc.update("insert into session_bindings (id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                bindingId, attemptId, user.getId(), deviceId, deviceId, team.getId(), team.getVersion(), accountId, revision, "synthetic-session", deviceId, "synthetic-session", "ACTIVE", "SERIAL_LOGIN_DIFF", "USER_CONFIRMED_INFERENCE", NOW);
    }

    @Test void administratorEndsOnlyTheLocalBindingAndImmediatelyClosesClientAccess() throws Exception {
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, token(user)).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
        mvc.perform(post("/api/v1/client/usage/events").header(HttpHeaders.AUTHORIZATION, token(user))
                        .contentType(MediaType.APPLICATION_JSON).content(usageBody()))
                .andExpect(status().isOk());
        Map<String, Object> meterBefore = jdbc.queryForMap("select * from meter_devices where id=?", deviceId);
        Map<String, Object> usageBefore = jdbc.queryForMap("select * from codex_usage_events where user_id=?", user.getId());

        release(bindingId, accountId);

        assertThat(jdbc.queryForObject("select status from session_bindings where id=?", String.class, bindingId)).isEqualTo("ENDED");
        assertThat(jdbc.queryForObject("select ended_at from session_bindings where id=?", java.sql.Timestamp.class, bindingId).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select active_device_id from session_bindings where id=?", UUID.class, bindingId)).isNull();
        assertThat(jdbc.queryForObject("select active_session_id from session_bindings where id=?", String.class, bindingId)).isNull();
        assertThat(jdbc.queryForObject("select session_id from session_bindings where id=?", String.class, bindingId)).isEqualTo("synthetic-session");
        assertThat(jdbc.queryForMap("select * from meter_devices where id=?", deviceId)).isEqualTo(meterBefore);
        assertThat(jdbc.queryForMap("select * from codex_usage_events where user_id=?", user.getId())).isEqualTo(usageBefore);
        verifyNoInteractions(official, officialSessions);
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, token(user)).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, token(user)).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(get("/api/v1/admin/session-bindings").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .param("officialAccountId", accountId.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].status").value("ENDED"));
    }

    @Test void repeatedReleasePreservesOriginalEndTimeAndCannotEndANewerBinding() throws Exception {
        release(bindingId, accountId);
        UUID nextBinding = UUID.randomUUID();
        UUID nextAttempt = UUID.randomUUID();
        jdbc.update("insert into session_binding_attempts (id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) select ?,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,'BOUND',created_at,expires_at from session_binding_attempts where id=?", nextAttempt, attemptId);
        jdbc.update("insert into session_bindings (id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) select ?,?,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,device_id,session_id,'ACTIVE',match_method,verification_level,created_at from session_bindings where id=?", nextBinding, nextAttempt, bindingId);
        when(clock.instant()).thenReturn(NOW.plusSeconds(30));
        release(bindingId, accountId);
        assertThat(jdbc.queryForObject("select ended_at from session_bindings where id=?", java.sql.Timestamp.class, bindingId).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("select status from session_bindings where id=?", String.class, nextBinding)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select status from session_binding_attempts where id=?", String.class, nextAttempt)).isEqualTo("BOUND");
        verifyNoInteractions(official, officialSessions);
    }

    @Test void releaseAllowsAFreshBindingAttemptWhileHistoricalBoundAttemptGrantsNoAccess() throws Exception {
        release(bindingId, accountId);
        var current = service.current(user.getId(), deviceId, deviceId);
        assertThat(current.binding()).isNull();
        assertThat(current.attempt().status()).isEqualTo("REJECTED");
        assertThat(current.attempt().errorCode()).isEqualTo("SESSION_BINDING_RELEASED");
        assertThat(current.attempt().binding().status()).isEqualTo("ENDED");
        assertThat(current.attempt().loginAllowed()).isFalse();
        var next = service.create(user.getId(), deviceId, deviceId, accountId);
        assertThat(next.id()).isNotEqualTo(attemptId);
        assertThat(next.status()).isEqualTo("QUEUED");
        when(official.refresh(accountId)).thenReturn(snapshot("existing"));
        assertThat(service.prepare(user.getId(), next.id()).status()).isEqualTo("READY_FOR_LOGIN");
        when(official.refresh(accountId)).thenReturn(snapshot("existing", "fresh-session"));
        assertThat(service.detect(user.getId(), next.id()).status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(service.confirm(user.getId(), next.id(), "fresh-session").binding().status()).isEqualTo("ACTIVE");
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, token(user)).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true));
    }

    @Test void releaseRequiresAdminRoleAndAuthentication() throws Exception {
        mvc.perform(post(RELEASE, bindingId).secure(true).contentType(MediaType.APPLICATION_JSON).content(body(accountId)))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(RELEASE, bindingId).secure(true).header(HttpHeaders.AUTHORIZATION, token(user))
                        .contentType(MediaType.APPLICATION_JSON).content(body(accountId)))
                .andExpect(status().isForbidden());
        assertActive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"officialAccountId\":null,\"acknowledgeUnbind\":true}", "{\"officialAccountId\":\"invalid\",\"acknowledgeUnbind\":true}", "missing", "false", "null"})
    void explicitAccountAndUnbindAcknowledgementAreRequired(String value) throws Exception {
        String content = switch (value) {
            case "missing" -> "{\"officialAccountId\":\"%s\"}".formatted(accountId);
            case "false", "null" -> "{\"officialAccountId\":\"%s\",\"acknowledgeUnbind\":%s}".formatted(accountId, value);
            default -> value;
        };
        mvc.perform(post(RELEASE, bindingId).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(content))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"));
        assertActive();
    }

    @Test void wrongAccountOrUnknownBindingIsNotFoundWithoutMutatingTheTarget() throws Exception {
        for (UUID target : List.of(bindingId, UUID.randomUUID())) {
            mvc.perform(post(RELEASE, target).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                            .contentType(MediaType.APPLICATION_JSON).content(body(UUID.randomUUID())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SESSION_BINDING_NOT_FOUND"));
        }
        assertActive();
    }

    @Test void configuredHttpAcceptsRemoteAndLoopbackWhileUnsupportedProtocolsRemainBlocked() throws Exception {
        mvc.perform(post(RELEASE, bindingId).with(request -> { request.setRemoteAddr("203.0.113.9"); request.setScheme("ftp"); return request; })
                        .header("X-Forwarded-Proto", "https")
                        .header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(accountId)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SESSION_BINDING_HTTPS_REQUIRED"));
        assertActive();
        mvc.perform(post(RELEASE, bindingId).with(request -> { request.setRemoteAddr("203.0.113.9"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(accountId)))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(post(RELEASE, bindingId).with(request -> { request.setRemoteAddr("127.0.0.1"); request.setLocalAddr("127.0.0.1"); request.setServerName("localhost"); return request; })
                        .header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(accountId)))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXECUTING", "UNKNOWN"})
    void officialLogoutFencePreventsReleaseEvenAfterPlanExpiry(String state) throws Exception {
        jdbc.update("insert into official_session_logout_plans (id,owner_id,official_account_id,mode,status,connection_revision,official_account_key,official_user_key,binding_fingerprint,session_fingerprint,preview_json,created_at,expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), admin.getId(), accountId, "ALL", state, revision, "account-" + accountId, "official-user", "a".repeat(64), "b".repeat(64), "{}", NOW.minusSeconds(120), NOW.minusSeconds(60));
        mvc.perform(post(RELEASE, bindingId).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body(accountId)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_SESSION_LOGOUT_BUSY"));
        assertActive();
        verifyNoInteractions(official, officialSessions);
    }

    @Test void userReleaseEndpointCannotReleaseAnotherUsersBinding() throws Exception {
        User other = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "unused", "Other user"));
        mvc.perform(post("/api/v1/client/session-bindings/bindings/{id}/release", bindingId).secure(true)
                        .header(HttpHeaders.AUTHORIZATION, token(other)))
                .andExpect(status().isForbidden());
        assertActive();
    }

    private void release(UUID id, UUID account) throws Exception {
        mvc.perform(post(RELEASE, id).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body(account)))
                .andExpect(status().isNoContent()).andExpect(header().string("Cache-Control", "no-store"));
    }

    private void assertActive() {
        assertThat(jdbc.queryForObject("select status from session_bindings where id=?", String.class, bindingId)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("select ended_at from session_bindings where id=?", java.sql.Timestamp.class, bindingId)).isNull();
    }

    private String token(User owner) { return "Bearer " + jwt.create(owner.getId(), owner.getRole().name(), owner.getSessionVersion()); }
    private String body(UUID account) { return "{\"officialAccountId\":\"%s\",\"acknowledgeUnbind\":true}".formatted(account); }
    private String usageBody() {
        return "{\"events\":[{\"eventId\":\"codex-local-usage:v1:%s\",\"occurredAt\":\"%s\",\"totalTokens\":10,\"source\":\"TOKEN_USAGE_RECORD\"}]}".formatted("a".repeat(64), NOW);
    }
    private OfficialConnectionRefreshResponse snapshot(String... sessionIds) {
        return new OfficialConnectionRefreshResponse(null,
                new OfficialAccountInfo("official-user", "synthetic@example.com", "Synthetic", "account-" + accountId, "Synthetic", "plus", "owner", false, true),
                new OfficialSessions(java.util.Arrays.stream(sessionIds).map(id -> new OfficialSessionInfo(id, id, "windows", "Synthetic", null, null, null, false, false, NOW, NOW, null, null, null, List.of("Codex"))).toList(), true), NOW);
    }
}
