package com.pingche.web.sessionbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.meter.MeterDevice;
import com.pingche.web.meter.MeterDeviceRepository;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.net.URI;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:client-access;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientAccessIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
    private static final String DEVICE_HEADER = "X-Quota-Guard-Device-ID";
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired OfficialAccountRepository accounts;
    @Autowired MeterDeviceRepository devices;
    @MockitoBean Clock clock;
    @MockitoBean OfficialConnectionService official;
    private User user;
    private UUID deviceId;
    private UUID accountId;
    private UUID revision;
    private Team team;
    private String authorization;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        accountId = accounts.saveAndFlush(new OfficialAccount("Gate " + UUID.randomUUID(), 1000, null, NOW)).getId();
        team = new Team("Gate " + UUID.randomUUID(), "", Team.Status.ACTIVE, 100, 100, NOW);
        team.updateTokenLimits(null, null, null, accountId, NOW);
        team = teams.saveAndFlush(team);
        user = new User(UUID.randomUUID() + "@example.com", "synthetic-unused-hash", "Gate user");
        user.updateQuotaPolicy(team.getId(), null, null, NOW);
        user = users.saveAndFlush(user);
        deviceId = UUID.randomUUID();
        devices.saveAndFlush(new MeterDevice(deviceId, user.getId(), UUID.randomUUID().toString().replace("-", "").repeat(2), NOW, NOW.plusSeconds(3600)));
        revision = UUID.randomUUID();
        jdbc.update("insert into official_account_connections (official_account_id,requested_account_id,authorization_cipher,bound_account_id,bound_user_id,status,revision,imported_at,version) values (?,?,?,?,?,?,?,?,0)",
                accountId, "account-" + accountId, "synthetic-no-secret", "account-" + accountId, "official-user", "CONNECTED", revision, NOW);
        authorization = token(user);
    }

    @Test void accessRequiresAuthenticationAndNeverTrustsClientBooleansOrUserIds() throws Exception {
        mvc.perform(get("/api/v1/client/access")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization)
                        .queryParam("allowed", "true").queryParam("userId", user.getId().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.allowed").value(false)).andExpect(jsonPath("$.reason").value("CLIENT_DEVICE_REQUIRED"));
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(DEVICE_HEADER, "not-a-uuid"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("CLIENT_DEVICE_INVALID"));
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization)
                        .header(DEVICE_HEADER, "1-1-1-1-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("CLIENT_DEVICE_INVALID"));
    }

    @Test void missingBindingDeniesDirectProtectedRequestsIncludingFutureClientRoutes() throws Exception {
        for (String path : new String[]{"/api/v1/client/official-quota", "/api/v1/client/quota/overview", "/api/v1/client/usage/analytics", "/api/v1/client/usage/events", "/api/v1/client/future-business"}) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        }
        mvc.perform(put("/api/v1/client/official-quota").header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"allowed\":true,\"bound\":true}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("SESSION_BINDING_REQUIRED"));
    }

    @Test void bootstrapWhiteListCannotBeExpandedByChangingTheMethodOrPathPrefix() throws Exception {
        mvc.perform(get("/api/v1/client/meter/enroll").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(put("/api/v1/client/usage/events").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(post("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(get("/api/v1/client/login-assistance/business").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(get("/api/v1/client/session-bindings/attempts/new-business").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(post("/api/v1/client/session-bindings/attempts/any/new-business").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        mvc.perform(get("/control/api/v1/client/quota/overview").contextPath("/control")
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/%61pi/v1/client/quota/overview", "/api/%761/client/quota/overview", "/api/v1/%63lient/quota/overview"})
    void encodedPrefixesCannotBypassTheBindingGate(String path) throws Exception {
        mvc.perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
    }

    @Test void currentOwnedBindingGrantsOnlyItsDeviceAndNeverCachesAnAllow() throws Exception {
        UUID bindingId = bind();
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.allowed").value(true)).andExpect(jsonPath("$.bindingId").value(bindingId.toString()));
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/usage/events").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/usage/analytics").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, UUID.randomUUID()))
                .andExpect(status().isForbidden());
        jdbc.update("update session_bindings set status='ENDED',active_device_id=null,active_session_id=null,ended_at=? where id=?", NOW, bindingId);
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isForbidden());
    }

    @Test void unboundLogoutSnapshotInvalidationKeepsAnAlreadyVerifiedBindingUsable() throws Exception {
        UUID bindingId = bind();
        // UNBOUND logout clears the snapshot but retains verified identity and revision.
        jdbc.update("update official_account_connections set status='UNVERIFIED',snapshot_cipher=null,last_success_at=null where official_account_id=?", accountId);
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.bindingId").value(bindingId.toString()));
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"revoked", "expired", "other-owner", "enrollment-mismatch", "team-version", "team-disabled", "user-team", "account", "revision", "connection-status", "connection-deleted", "quarantined", "executing", "unknown", "duplicate-official", "unverified-account-missing", "unverified-user-missing", "unverified-account-mismatch", "unverified-new-revision", "invalid-response"})
    void changingAnyPersistedBindingContextImmediatelyClosesTheGate(String change) throws Exception {
        bind();
        switch (change) {
            case "revoked" -> jdbc.update("update meter_devices set status='REVOKED' where id=?", deviceId);
            case "expired" -> jdbc.update("update meter_devices set expires_at=? where id=?", NOW, deviceId);
            case "other-owner" -> jdbc.update("update meter_devices set user_id=? where id=?", users.saveAndFlush(new User(UUID.randomUUID()+"@example.com", "unused", "Other")).getId(), deviceId);
            case "enrollment-mismatch" -> jdbc.update("update session_bindings set enrollment_id=? where user_id=?", UUID.randomUUID(), user.getId());
            case "team-version" -> jdbc.update("update teams set version=version+1 where id=?", team.getId());
            case "team-disabled" -> jdbc.update("update teams set status='DISABLED' where id=?", team.getId());
            case "user-team" -> jdbc.update("update users set team_id=null where id=?", user.getId());
            case "account" -> jdbc.update("update teams set official_account_id=null where id=?", team.getId());
            case "revision" -> jdbc.update("update official_account_connections set revision=? where official_account_id=?", UUID.randomUUID(), accountId);
            case "connection-status" -> jdbc.update("update official_account_connections set status='AUTH_REQUIRED' where official_account_id=?", accountId);
            case "invalid-response" -> jdbc.update("update official_account_connections set status='INVALID_RESPONSE' where official_account_id=?", accountId);
            case "unverified-account-missing" -> jdbc.update("update official_account_connections set status='UNVERIFIED',bound_account_id=null where official_account_id=?", accountId);
            case "unverified-user-missing" -> jdbc.update("update official_account_connections set status='UNVERIFIED',bound_user_id=null where official_account_id=?", accountId);
            case "unverified-account-mismatch" -> jdbc.update("update official_account_connections set status='UNVERIFIED',bound_account_id='other' where official_account_id=?", accountId);
            case "unverified-new-revision" -> jdbc.update("update official_account_connections set status='UNVERIFIED',revision=? where official_account_id=?", UUID.randomUUID(), accountId);
            case "connection-deleted" -> jdbc.update("delete from official_account_connections where official_account_id=?", accountId);
            case "quarantined" -> jdbc.update("insert into session_binding_accounts (official_account_id,quarantined,updated_at) values (?,true,?)", accountId, NOW);
            case "executing", "unknown" -> logoutFence(change.toUpperCase());
            case "duplicate-official" -> {
                UUID other = accounts.saveAndFlush(new OfficialAccount("Duplicate " + UUID.randomUUID(), 1000, null, NOW)).getId();
                jdbc.update("insert into official_account_connections (official_account_id,requested_account_id,authorization_cipher,status,revision,imported_at,version) values (?,?,?,?,?,?,0)", other, "account-"+accountId, "synthetic-no-secret", "UNVERIFIED", UUID.randomUUID(), NOW);
            }
            default -> throw new IllegalArgumentException(change);
        }
        mvc.perform(get("/api/v1/client/access").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.reason").value("CLIENT_BINDING_INVALID"));
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        assertThat(jdbc.queryForObject("select status from session_bindings where user_id=?", String.class, user.getId())).isEqualTo("ACTIVE");
    }

    @Test void anotherUsersValidBindingCannotAuthorizeTheAuthenticatedUser() throws Exception {
        bind();
        User other = users.saveAndFlush(new User(UUID.randomUUID()+"@example.com", "unused", "Other"));
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, token(other)).header(DEVICE_HEADER, deviceId))
                .andExpect(status().isForbidden());
    }

    @Test void bootstrapAndAdministrativeAccessRemainAvailableWithoutBinding() throws Exception {
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/login-assistance").header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/session-bindings/current").secure(true).header(HttpHeaders.AUTHORIZATION, authorization)
                        .queryParam("deviceId", deviceId.toString()).queryParam("enrollmentId", deviceId.toString()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/client/session-bindings/attempts").secure(true).header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"%s\",\"enrollmentId\":\"%s\"}".formatted(deviceId, deviceId)))
                .andExpect(status().isOk());
        User admin = users.saveAndFlush(User.bootstrapAdministrator(UUID.randomUUID()+"@example.com", "unused", "Admin"));
        mvc.perform(get("/api/v1/admin/dashboard").header(HttpHeaders.AUTHORIZATION, token(admin))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/admin/browser-logins").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGIN_HELPER_RETIRED"));
        mvc.perform(get("/api/v1/client/quota/overview").header(HttpHeaders.AUTHORIZATION, token(admin))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/dashboard").header(HttpHeaders.AUTHORIZATION, authorization)).andExpect(status().isForbidden());
    }

    @Test void identityLoginRefreshAndLogoutDoNotRequireADeviceBinding() throws Exception {
        String email = UUID.randomUUID() + "@example.com";
        String credentials = "{\"email\":\"%s\",\"password\":\"synthetic-test-password-long-enough\"}".formatted(email);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials.substring(0, credentials.length()-1) + ",\"displayName\":\"Bootstrap user\"}"))
                .andExpect(status().isCreated());
        String login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(credentials))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String refreshed = mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(json.readTree(login).path("refreshToken").asString())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/v1/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + json.readTree(refreshed).path("accessToken").asString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(json.readTree(refreshed).path("refreshToken").asString())))
                .andExpect(status().isNoContent());
    }

    @Test void collectionContinuesWithoutBindingAndItsResponseDoesNotExposeQuota() throws Exception {
        mvc.perform(post("/api/v1/client/usage/events").header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON).content(usageBody()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.acceptedEvents").value(1))
                .andExpect(jsonPath("$.userId").doesNotExist()).andExpect(jsonPath("$.team").doesNotExist())
                .andExpect(jsonPath("$.fiveHour").doesNotExist());
        assertThat(jdbc.queryForObject("select count(*) from codex_usage_events where user_id=?", Integer.class, user.getId())).isEqualTo(1);
        String enrolled = mvc.perform(post("/api/v1/client/meter/enroll").header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String meterToken = json.readTree(enrolled).path("deviceToken").asString();
        mvc.perform(post("/api/v1/meter/heartbeat").header("X-Quota-Guard-Meter", meterToken)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/meter/usage/events").header("X-Quota-Guard-Meter", meterToken)
                        .contentType(MediaType.APPLICATION_JSON).content(usageBody())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/client/quota/overview").header("X-Quota-Guard-Meter", meterToken)).andExpect(status().isUnauthorized());
    }

    @Test void deviceHeaderIsAllowedByCorsPreflight() throws Exception {
        mvc.perform(options("/api/v1/client/access").header(HttpHeaders.ORIGIN, "http://localhost:1420")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,x-quota-guard-device-id"))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        org.hamcrest.Matchers.containsString("x-quota-guard-device-id")));
    }

    @Test void boundClientReadsOnlyTheAssociatedServerQuotaWithNoStore() throws Exception {
        bind();
        String snapshot = """
            {"planType":"pro","windows":[{"limitId":"codex","slot":"primary","windowDurationMins":300,"usedPercent":27.5,"resetsAt":null}],"fetchedAt":"%s","receivedAt":"%s"}
            """.formatted(NOW, NOW);
        jdbc.update("insert into official_account_quota_polls (official_account_id,status,last_success_at,snapshot_json,connection_revision) values (?,'SUCCESS',?,?,?)", accountId, NOW, snapshot, revision);
        mvc.perform(get("/api/v1/client/official-quota").header(HttpHeaders.AUTHORIZATION, authorization).header(DEVICE_HEADER, deviceId)
                        .queryParam("userId", UUID.randomUUID().toString()).queryParam("officialAccountId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.source").value("SERVER"))
                .andExpect(jsonPath("$.windows[0].usedPercent").value(27.5))
                .andExpect(jsonPath("$.stale").value(false))
                .andExpect(jsonPath("$.authorization").doesNotExist()).andExpect(jsonPath("$.cookie").doesNotExist());
        mvc.perform(get("/api/v1/client/official-quota")).andExpect(status().isUnauthorized());
    }

    private UUID bind() {
        UUID attempt = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        jdbc.update("insert into session_binding_attempts (id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values (?,?,?,?,?,?,?,?,?,?,?)", attempt, user.getId(), deviceId, deviceId, team.getId(), team.getVersion(), accountId, revision, "BOUND", NOW, NOW.plusSeconds(1800));
        jdbc.update("insert into session_bindings (id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", binding, attempt, user.getId(), deviceId, deviceId, team.getId(), team.getVersion(), accountId, revision, "synthetic-session", deviceId, "synthetic-session", "ACTIVE", "SERIAL_LOGIN_DIFF", "USER_CONFIRMED_INFERENCE", NOW);
        return binding;
    }

    private void logoutFence(String status) {
        jdbc.update("insert into official_session_logout_plans (id,owner_id,official_account_id,mode,status,connection_revision,official_account_key,official_user_key,binding_fingerprint,session_fingerprint,preview_json,created_at,expires_at) values (?,?,?,?,?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), user.getId(), accountId, "ALL", status, revision, "account-"+accountId, "official-user", "a".repeat(64), "b".repeat(64), "{}", NOW, NOW.plusSeconds(60));
    }

    private String token(User owner) { return "Bearer " + jwt.create(owner.getId(), owner.getRole().name()); }
    private String usageBody() {
        return "{\"events\":[{\"eventId\":\"codex-local-usage:v1:%s\",\"occurredAt\":\"%s\",\"totalTokens\":10,\"source\":\"TOKEN_USAGE_RECORD\"}]}".formatted("a".repeat(64), NOW);
    }
}
