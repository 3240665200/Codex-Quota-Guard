package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:official-preset;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OfficialAuthorizationPresetIntegrationTest {
    private static final String METADATA = "/api/v1/admin/official-authorization-preset";
    private static final String VALID = "{\"requestedAccountId\":\"preset-account\",\"authorization\":\"Bearer fake-preset-secret\",\"cookie\":\"__Secure-next-auth.session-token=fake-cookie-secret\"}";
    private static final Path PRESET = createPreset();
    @Autowired MockMvc mvc;
    @Autowired OfficialAccountRepository accounts;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @MockitoBean OfficialSessionClient client;
    private OfficialAccount account;
    private User admin;
    private User member;

    @DynamicPropertySource static void presetProperty(DynamicPropertyRegistry registry) {
        registry.add("OFFICIAL_AUTHORIZATION_PRESET_FILE", PRESET::toString);
    }
    @AfterAll static void removePreset() throws IOException { Files.deleteIfExists(PRESET); }
    @BeforeEach void setup() throws IOException {
        Files.writeString(PRESET, VALID);
        jdbc.update("delete from official_account_connections");
        accounts.deleteAll();
        users.deleteAll();
        admin = users.saveAndFlush(User.bootstrapAdministrator("preset-admin@example.com", "synthetic-unused-hash", "Admin"));
        member = users.saveAndFlush(new User("preset-member@example.com", "synthetic-unused-hash", "Member"));
        account = accounts.saveAndFlush(new OfficialAccount("Preset target", 1234, member.getId(), Instant.now()));
    }

    @Test void metadataAndApplyAreAdminOnlyAndEveryResponseIsNoStore() throws Exception {
        for (var user : List.of(member, admin)) {
            if (user == admin) continue;
            mvc.perform(get(METADATA).header(HttpHeaders.AUTHORIZATION, token(user))).andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
            mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(user)).contentType(MediaType.APPLICATION_JSON)
                            .content(body(0, "a".repeat(64)))).andExpect(status().isForbidden())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        }
        mvc.perform(get(METADATA)).andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mvc.perform(post(url()).contentType(MediaType.APPLICATION_JSON).content(body(0, "a".repeat(64))))
                .andExpect(status().isUnauthorized()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        assertThat(jdbc.queryForObject("select count(*) from official_account_connections", Integer.class)).isZero();
        verifyNoInteractions(client);
    }

    @Test void httpAndHttpsExposeOnlyMetadataAndApplyEncryptedCredentialsWithoutCallingOfficialService() throws Exception {
        for (boolean secure : new boolean[] {false, true}) {
            String metadata = mvc.perform(get(METADATA).secure(secure).header(HttpHeaders.AUTHORIZATION, token(admin)))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.requestedAccountId").value("preset-account"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(metadata).doesNotContain("fake-preset-secret", "fake-cookie-secret", "authorization", PRESET.toString());
            assertThat(jdbc.queryForObject("select count(*) from official_account_connections", Integer.class)).isZero();
            String response = mvc.perform(post(url()).secure(secure).header(HttpHeaders.AUTHORIZATION, token(admin))
                            .contentType(MediaType.APPLICATION_JSON).content(body(0, mapper.readTree(metadata).path("revision").asString())))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.status").value("UNVERIFIED"))
                    .andExpect(jsonPath("$.hasAuthorization").value(true)).andExpect(jsonPath("$.hasCookie").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain("fake-preset-secret", "fake-cookie-secret", "Cipher");
            var stored = jdbc.queryForMap("select * from official_account_connections where official_account_id=?", account.getId());
            assertThat(stored.get("authorization_cipher").toString()).doesNotContain("fake-preset-secret");
            assertThat(stored.get("cookie_cipher").toString()).doesNotContain("fake-cookie-secret");
            assertThat(stored.get("last_attempt_at")).isNull();
            assertThat(accounts.findById(account.getId()).orElseThrow().getReporterUserId()).isEqualTo(member.getId());
            jdbc.update("delete from official_account_connections");
        }
        verifyNoInteractions(client);
    }

    @Test void changedFileStaleVersionAndBoundDifferentIdentityCannotOverwriteConnection() throws Exception {
        String revision = revision();
        apply(0, revision);
        long version = version();
        apply(version, revision);
        mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(version, revision)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_CONNECTION_VERSION_CONFLICT"));
        jdbc.update("update official_account_connections set bound_account_id=? where official_account_id=?", "different-bound-account", account.getId());
        mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(version(), revision)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_IDENTITY_CHANGE_REQUIRES_DISCONNECT"));
        Files.writeString(PRESET, VALID.replace("fake-preset-secret", "replacement-secret"));
        mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(version(), revision)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OFFICIAL_PRESET_REVISION_CONFLICT"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        verifyNoInteractions(client);
    }

    @Test void invalidFileMalformedRequestUnsupportedTransportAndProxyAreSafeFailures() throws Exception {
        String revision = revision();
        mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("version", 0, "proxyNodeId", UUID.randomUUID(), "presetRevision", revision))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_PROXY_UNAVAILABLE"));
        for (String body : List.of("{}", "{\"version\":-1,\"presetRevision\":\"bad\"}", "{\"version\":0,\"presetRevision\":null}"))
            mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));
        mvc.perform(get(METADATA).with(request -> {request.setScheme("ftp"); return request;}).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        mvc.perform(post(url()).with(request -> {request.setScheme("ftp"); return request;}).header(HttpHeaders.AUTHORIZATION, token(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body(0, revision)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("OFFICIAL_HTTPS_REQUIRED"));
        Files.writeString(PRESET, "private-malformed-secret");
        mvc.perform(get(METADATA).header(HttpHeaders.AUTHORIZATION, token(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.requestedAccountId").isEmpty())
                .andExpect(jsonPath("$.revision").isEmpty()).andExpect(jsonPath("$.errorCode").value("OFFICIAL_PRESET_INVALID"));
        String response = mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(0, revision)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("OFFICIAL_PRESET_INVALID"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store")).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private-malformed-secret", PRESET.toString());
        verifyNoInteractions(client);
    }

    private String revision() throws Exception {
        return mapper.readTree(mvc.perform(get(METADATA).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("revision").asString();
    }
    private void apply(long version, String revision) throws Exception {
        mvc.perform(post(url()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON).content(body(version, revision)))
                .andExpect(status().isOk());
    }
    private long version() { return jdbc.queryForObject("select version from official_account_connections where official_account_id=?", Long.class, account.getId()); }
    private String body(long version, String revision) { return mapper.writeValueAsString(Map.of("version", version, "presetRevision", revision)); }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }
    private String url() { return "/api/v1/admin/official-accounts/" + account.getId() + "/connection/preset"; }
    private static Path createPreset() {
        try { return Files.createTempFile("official-preset-test-", ".json"); }
        catch (IOException error) { throw new IllegalStateException("Cannot create synthetic test preset"); }
    }
}
