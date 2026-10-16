package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:pingche-official-sessions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class OfficialSessionIntegrationTest {
    private UUID proxyNodeId;
    private static final Instant NOW = Instant.parse("2026-10-14T10:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired OfficialAccountRepository accounts;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository refreshSessions;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean OfficialSessionClient client;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private OfficialAccount account;
    private Team team;
    private User admin;
    private User member;

    @BeforeEach
    void setup() {
        proxyNodeId = SyntheticProxyNodeFixture.insert(jdbc);
        now.set(NOW);
        when(clock.instant()).thenAnswer(call -> now.get());
        if (jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'official_account_connections'", Integer.class) > 0)
            jdbc.update("delete from official_account_connections");
        refreshSessions.deleteAll();
        users.deleteAll();
        teams.deleteAll();
        accounts.deleteAll();
        member = users.saveAndFlush(new User("session-member@example.com", "synthetic-unused-hash", "Member"));
        admin = users.saveAndFlush(User.bootstrapAdministrator("session-admin@example.com", "synthetic-unused-hash", "Admin"));
        account = accounts.saveAndFlush(new OfficialAccount("Existing local account", 1234, member.getId(), NOW));
        team = new Team("Session team", "", Team.Status.ACTIVE, 100, 100, NOW);
        team.updateTokenLimits(null, null, 1234L, account.getId(), NOW);
        team = teams.saveAndFlush(team);
        when(client.fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId))).thenReturn(snapshot());
    }

    @Test
    void adminOnlySupportedTransportAndNoStoreApplyToEverySensitiveReadAndWrite() throws Exception {
        for (String path : List.of(connectionUrl(), sessionsUrl())) {
            mvc.perform(get(path).secure(true)).andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
            mvc.perform(get(path).secure(true).header(HttpHeaders.AUTHORIZATION, token(member)))
                    .andExpect(status().isForbidden()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
            mvc.perform(get(path).with(http -> { http.setScheme("ftp"); return http; }).header(HttpHeaders.AUTHORIZATION, token(admin)).header("X-Forwarded-Proto", "https"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        }
        mvc.perform(put(connectionUrl()).with(http -> { http.setScheme("ftp"); return http; }).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Bearer synthetic-access", "official-account-one", 0)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        verifyNoInteractions(client);
    }

    @Test
    void importIsEncryptedUnverifiedAndDoesNotChangeExistingAccountRelationships() throws Exception {
        mvc.perform(get(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false))
                .andExpect(jsonPath("$.status").value("NOT_CONFIGURED")).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.encryptionReady").value(true));
        String response = save("Bearer synthetic-access", "official-account-one", 0);
        assertThat(response).doesNotContain("synthetic-access", "authorizationCipher", "cookieCipher");
        assertThat(mapper.readTree(response).path("status").asString()).isEqualTo("UNVERIFIED");
        assertThat(mapper.readTree(response).path("hasAuthorization").asBoolean()).isTrue();
        Map<String, Object> stored = jdbc.queryForMap("select * from official_account_connections where official_account_id = ?", account.getId());
        assertThat(stored.get("authorization_cipher").toString()).doesNotContain("synthetic-access");
        OfficialAccount unchanged = accounts.findById(account.getId()).orElseThrow();
        assertThat(unchanged.getEstimatedTotalTokens()).isEqualTo(1234);
        assertThat(unchanged.getReporterUserId()).isEqualTo(member.getId());
        assertThat(unchanged.getLabel()).isEqualTo("Existing local account");
        assertThat(teams.findById(team.getId()).orElseThrow().getOfficialAccountId()).isEqualTo(account.getId());
        mvc.perform(get(sessionsUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_SNAPSHOT_UNAVAILABLE"));
        verifyNoInteractions(client);
    }

    @Test
    void refreshPublishesSafeVerifiedSnapshotAndGetDoesNotRefetch() throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        String refreshed = mvc.perform(post(connectionUrl() + "/refresh").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.connection.status").value("CONNECTED"))
                .andExpect(jsonPath("$.account.accountId").value("official-account-one"))
                .andExpect(jsonPath("$.account.userId").value("official-user-one"))
                .andExpect(jsonPath("$.sessions.devices[0].applications[0]").value("Codex"))
                .andExpect(jsonPath("$.sessions.devices[0].currentDevice").value(true))
                .andExpect(jsonPath("$.sessions.devices[0].ownerUserId").doesNotExist())
                .andExpect(jsonPath("$.fetchedAt").value(NOW.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(refreshed).doesNotContain("synthetic-access", "cookie", "Cipher", "hashedDeviceId");
        String storedSnapshot = jdbc.queryForObject("select snapshot_cipher from official_account_connections where official_account_id = ?",
                String.class, account.getId());
        assertThat(storedSnapshot).doesNotContain("owner@example.com", "session-one", "Synthetic City");
        mvc.perform(get(sessionsUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.email").value("owner@example.com"))
                .andExpect(jsonPath("$.sessions.devices.length()").value(1));
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId));
    }

    @Test
    void replacingAuthorizationClearsSnapshotButVerifiedIdentityCannotBeReboundUntilDeleted() throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        refresh();
        long version = version();
        mvc.perform(put(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Bearer replacement", "different-official-account", version)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_IDENTITY_CHANGE_REQUIRES_DISCONNECT"));
        save("Bearer replacement", "official-account-one", version);
        mvc.perform(get(sessionsUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_SNAPSHOT_UNAVAILABLE"));
        mvc.perform(delete(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isNoContent()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        save("Bearer second-account", "different-official-account", 0);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId));
        assertThat(accounts.findById(account.getId())).isPresent();
    }

    @Test
    void staleVersionsInvalidHeadersAndMissingAccountsAreSafeFailures() throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        save("Bearer replacement", "official-account-one", version());
        mvc.perform(put(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(body("Bearer stale", "official-account-one", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_CONNECTION_VERSION_CONFLICT"));
        for (String secret : List.of("", "not-bearer", "Bearer injected\r\nHost: attacker", "Bearer " + "x".repeat(16_385))) {
            String response = mvc.perform(put(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                            .contentType(MediaType.APPLICATION_JSON).content(body(secret, "official-account-one", version())))
                    .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("attacker", "not-bearer");
        }
        mvc.perform(get("/api/v1/admin/official-accounts/" + UUID.randomUUID() + "/connection").secure(true)
                        .header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("OFFICIAL_ACCOUNT_NOT_FOUND"));
    }

    @Test
    void challengeIsDistinguishedFromInvalidCredentialsAndPreservedForThePage() throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        when(client.fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId))).thenThrow(
                new DomainException("OFFICIAL_CHALLENGE_REQUIRED", "Verification required", HttpStatus.BAD_GATEWAY));
        mvc.perform(post(connectionUrl() + "/refresh").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OFFICIAL_CHALLENGE_REQUIRED"));
        mvc.perform(get(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCESS_BLOCKED"))
                .andExpect(jsonPath("$.lastErrorCode").value("OFFICIAL_CHALLENGE_REQUIRED"));
    }

    @Test
    void providerFailurePersistsOnlySafeStatusAndNeverServesStaleSnapshotAsFresh() throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        refresh();
        now.set(NOW.plusSeconds(11));
        when(client.fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId))).thenThrow(
                new DomainException("OFFICIAL_ACCESS_BLOCKED", "Official access is blocked", HttpStatus.BAD_GATEWAY));
        mvc.perform(post(connectionUrl() + "/refresh").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OFFICIAL_ACCESS_BLOCKED"));
        mvc.perform(get(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACCESS_BLOCKED"))
                .andExpect(jsonPath("$.lastErrorCode").value("OFFICIAL_ACCESS_BLOCKED"));
        mvc.perform(get(sessionsUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_SNAPSHOT_UNAVAILABLE"));
    }

    @Test
    void unexpectedProviderExceptionNeverLeaksIntoResponseOrLogs(CapturedOutput output) throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        when(client.fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId))).thenThrow(new IllegalStateException("private-upstream-credential-body"));
        String response = mvc.perform(post(connectionUrl() + "/refresh").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("OFFICIAL_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private-upstream-credential-body");
        assertThat(output.getAll()).doesNotContain("private-upstream-credential-body");
    }

    @Test
    void successfulRefreshHasCooldownAndNeverStartsAutomaticRetries() throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        refresh();
        mvc.perform(post(connectionUrl() + "/refresh").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("OFFICIAL_REFRESH_RATE_LIMITED"));
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId));
    }

    @Test
    void inFlightOldAuthorizationCannotRepopulateSnapshotAfterReplacement() throws Exception {
        save("Bearer synthetic-access", "official-account-one", 0);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.fetch(any(OfficialSessionCredentials.class), eq(proxyNodeId))).thenAnswer(call -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return snapshot();
        });
        CompletableFuture<Integer> old = CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(post(connectionUrl() + "/refresh").secure(true)
                        .header(HttpHeaders.AUTHORIZATION, token(admin))).andReturn().getResponse().getStatus();
            } catch (Exception error) { throw new IllegalStateException(error); }
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            mvc.perform(post(connectionUrl() + "/refresh").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_REFRESH_BUSY"));
            save("Bearer replacement", "official-account-one", version());
        }
        finally { release.countDown(); }
        assertThat(old.get(5, TimeUnit.SECONDS)).isEqualTo(409);
        mvc.perform(get(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNVERIFIED"));
        mvc.perform(get(sessionsUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isConflict());
    }

    private OfficialSessionSnapshot snapshot() {
        OfficialAccountInfo info = new OfficialAccountInfo("official-user-one", "owner@example.com", "Owner",
                "official-account-one", "Personal", "plus", "owner", false, true);
        OfficialSessionInfo device = new OfficialSessionInfo("row-one", "session-one", "windows", "Codex desktop",
                "PC", "Windows PC", "11", true, false, NOW, NOW.minusSeconds(100), "Synthetic City", "SC", "ZZ", List.of("Codex"));
        return new OfficialSessionSnapshot(info, new OfficialSessions(List.of(device), true));
    }

    private String save(String authorization, String requestedId, long version) throws Exception {
        return mvc.perform(put(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body(authorization, requestedId, version)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    private void refresh() throws Exception {
        mvc.perform(post(connectionUrl() + "/refresh").secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk());
    }

    private long version() throws Exception {
        String response = mvc.perform(get(connectionUrl()).secure(true).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("version").asLong();
    }

    private String body(String authorization, String requestedId, long version) {
        return mapper.writeValueAsString(Map.of("authorization", authorization, "requestedAccountId", requestedId, "proxyNodeId", proxyNodeId, "version", version));
    }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }
    private String connectionUrl() { return "/api/v1/admin/official-accounts/" + account.getId() + "/connection"; }
    private String sessionsUrl() { return "/api/v1/admin/official-accounts/" + account.getId() + "/sessions"; }
}
