package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CodexAuthorizationIntegrationTest {

    private static final String BASE = "/api/v1/admin/codex-accounts/authorizations";
    private static final String SECRET = "never-send-this-upstream-secret";
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository refreshSessions;
    @Autowired CodexAccountRepository accounts;
    @Autowired CodexCredentialCipher cipher;
    @Autowired CodexAuthorizationService authorizations;
    @MockitoBean CodexLoginGateway gateway;
    @MockitoBean Clock clock;
    private User administrator;
    private CodexLoginGateway.Login login;

    @BeforeEach
    void prepare() {
        when(clock.instant()).thenReturn(NOW);
        accounts.deleteAll();
        refreshSessions.deleteAll();
        users.deleteAll();
        // Expire old owners before configuring a new mock login.
        authorizations.maintain();
        administrator = admin("admin@example.com");
        login = mock(CodexLoginGateway.Login.class);
        when(login.verificationUrl()).thenReturn("https://auth.openai.com/codex/device");
        when(login.userCode()).thenReturn("ABCD-1234");
        when(login.poll()).thenReturn(Optional.empty());
        when(gateway.open(any(CodexLoginMode.class))).thenReturn(login);
    }

    @Test
    void authorizationsRequireAdministratorAndArePrivateToTheirOwner() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isUnauthorized());
        User ordinary = users.saveAndFlush(new User("user@example.com", "unused", "User"));
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(ordinary))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isForbidden());
        JsonNode result = start();
        String endpoint = BASE + "/" + result.path("id").asString();
        User other = admin("other@example.com");
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(delete(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(other)))
                .andExpect(status().isNotFound());
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(ordinary)))
                .andExpect(status().isForbidden());
        mvc.perform(delete(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isNoContent());
    }

    @ParameterizedTest
    @EnumSource(CodexLoginMode.class)
    void successEncryptsCredentialsAndOnlyReturnsMetadata(CodexLoginMode mode) throws Exception {
        var started = authorizations.start(administrator.getId(), "Test account", mode);
        when(login.poll()).thenReturn(Optional.of(authorized()));
        String response = mvc.perform(get(BASE + "/" + started.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.account.email").value("authorized@example.com"))
                .andExpect(jsonPath("$.userCode").isEmpty())
                .andExpect(jsonPath("$.authUrl").isEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SECRET, "access_token", "refresh_token", "ciphertext");
        assertThat(accounts.count()).isEqualTo(1);
        CodexAccount saved = accounts.findAll().get(0);
        assertThat(saved.getCredentialCiphertext()).doesNotContain(SECRET);
        assertThat(new String(cipher.decrypt(saved.getId(), saved.getCredentialCiphertext(), saved.getCredentialIv())))
                .contains(SECRET);
        verify(login).close();
        mvc.perform(get(BASE + "/" + started.id())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(accounts.count()).isEqualTo(1);
    }

    @Test
    void cancelIsIdempotentAndCannotLaterCreateAnAccount() throws Exception {
        String endpoint = BASE + "/" + start().path("id").asString();
        mvc.perform(delete(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isNoContent());
        mvc.perform(delete(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isNoContent());
        when(login.poll()).thenReturn(Optional.of(authorized()));
        mvc.perform(get(endpoint).header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.userCode").isEmpty());
        assertThat(accounts.count()).isZero();
        verify(login).close();
    }

    @Test
    void expiryCleansUpEvenWithoutBrowserPolling() throws Exception {
        UUID id = UUID.fromString(start().path("id").asString());
        when(clock.instant()).thenReturn(NOW.plusSeconds(601));
        authorizations.maintain();
        assertThat(authorizations.get(administrator.getId(), id).status())
                .isEqualTo(CodexAuthorizationResponse.Status.EXPIRED);
        verify(login).close();
        assertThat(accounts.count()).isZero();
    }

    @Test
    void forceLogoutInvalidatesThePendingUpstreamAuthorization() throws Exception {
        UUID id = UUID.fromString(start().path("id").asString());
        when(login.poll()).thenReturn(Optional.of(authorized()));
        administrator.invalidateSessions();
        users.saveAndFlush(administrator);
        authorizations.maintain();
        assertThat(authorizations.get(administrator.getId(), id).status())
                .isEqualTo(CodexAuthorizationResponse.Status.CANCELLED);
        assertThat(accounts.count()).isZero();
        verify(login).close();
    }

    @Test
    void validatesInputAndPreventsMultiplePendingProcessesForOneAdministrator() throws Exception {
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"   \"}"))
                .andExpect(status().isBadRequest());
        start();
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isTooManyRequests());
        verify(gateway).open(CodexLoginMode.DEVICE_CODE);
    }

    @Test
    void errorsDoNotReflectUpstreamSecrets() throws Exception {
        when(gateway.open(any(CodexLoginMode.class))).thenThrow(new IllegalStateException(SECRET));
        JsonNode result = startResult();
        assertThat(result.path("status").asString()).isEqualTo("FAILED");
        assertThat(result.toString()).doesNotContain(SECRET);
        assertThat(accounts.count()).isZero();
    }

    @Test
    void cleanupFailureStopsPersistenceAndIsRetriedByMaintenance() throws Exception {
        JsonNode started = start();
        when(login.poll()).thenReturn(Optional.of(authorized()));
        doThrow(new IllegalStateException(SECRET)).when(login).close();
        CodexAuthorizationResponse result = authorizations.get(administrator.getId(),
                UUID.fromString(started.path("id").asString()));
        assertThat(result.status()).isEqualTo(CodexAuthorizationResponse.Status.FAILED);
        assertThat(result.errorCode()).isEqualTo("CODEX_AUTHORIZATION_CLEANUP_FAILED");
        assertThat(accounts.count()).isZero();
        assertThat(mapper.writeValueAsString(result)).doesNotContain(SECRET);
        doNothing().when(login).close();
        authorizations.maintain();
        verify(login, atLeast(2)).close();
    }

    @Test
    void browserLoginIsSelectedExplicitlyAndOnlyOneCallbackListenerIsAllowed() throws Exception {
        when(login.userCode()).thenReturn(null);
        when(login.verificationUrl()).thenReturn(null);
        when(login.authUrl()).thenReturn(CodexLoginUrlTest.VALID_URL);
        String response = mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Browser account\",\"mode\":\"BROWSER\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("BROWSER"))
                .andExpect(jsonPath("$.authUrl").value(CodexLoginUrlTest.VALID_URL))
                .andExpect(jsonPath("$.userCode").isEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(mapper.readTree(response).path("id").asString());
        assertThat(authorizations.get(administrator.getId(), id).toString()).doesNotContain("test-state");
        User other = admin("browser-admin@example.com");
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Browser account\",\"mode\":\"BROWSER\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CODEX_BROWSER_LOGIN_BUSY"));
        verify(gateway, times(1)).open(CodexLoginMode.BROWSER);
        authorizations.cancel(administrator.getId(), id);
        assertThat(authorizations.get(administrator.getId(), id).authUrl()).isNull();
    }

    @Test
    void refusesUnknownLoginModesWithoutStartingAProcess() throws Exception {
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Test\",\"mode\":\"RAW_TOKEN\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(gateway);
    }

    private JsonNode start() throws Exception {
        JsonNode result = startResult();
        assertThat(result.path("status").asString()).isEqualTo("PENDING");
        assertThat(result.path("userCode").asString()).isEqualTo("ABCD-1234");
        return result;
    }

    private JsonNode startResult() throws Exception {
        return mapper.readTree(mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON).content(body()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString());
    }

    private CodexLoginGateway.AuthorizedAccount authorized() {
        return new CodexLoginGateway.AuthorizedAccount("authorized@example.com",
                mapper.readTree("{\"tokens\":{\"access_token\":\"" + SECRET
                        + "\",\"refresh_token\":\"refresh-secret\"}}"));
    }

    private String body() { return "{\"displayName\":\"Managed account\"}"; }
    private User admin(String email) {
        return users.saveAndFlush(User.bootstrapAdministrator(email, "unused", "Admin"));
    }
    private String bearer(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name()); }
}
