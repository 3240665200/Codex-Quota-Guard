package com.pingche.web.codex;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthResponse;
import com.pingche.web.auth.AuthService;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class CodexAccountAssignmentIntegrationTest {

    private static final String PASSWORD = "admin-test-password";
    private static final String CREDENTIAL_SECRET = "credential-secret-marker";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private AuthService authService;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private UserRepository users;

    @Autowired
    private RefreshSessionRepository sessions;

    @Autowired
    private CodexAccountRepository accounts;

    @BeforeEach
    void cleanDatabase() {
        sessions.deleteAll();
        users.deleteAll();
        accounts.deleteAll();
    }

    @Test
    void administratorCanAssignAndUnassignWithoutLeakingCredentials()
            throws Exception {
        User administrator = createAdministrator();
        AuthResponse userSession = authService.register(
                "member@example.com",
                PASSWORD,
                "Member"
        );
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID accountId = createManagedAccount(administrator, "Managed quota account");

        mvc.perform(get("/api/v1/client/account/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(false))
                .andExpect(jsonPath("$.user.email").value("member@example.com"))
                .andExpect(jsonPath("$.codexAccount").value(nullValue()));

        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codexAccountId\":\"%s\"}".formatted(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(accountId.toString()))
                .andExpect(jsonPath("$.displayName").value("Managed quota account"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andExpect(jsonPath("$.credentialCiphertext").doesNotExist())
                .andExpect(jsonPath("$.credentialIv").doesNotExist())
                .andExpect(content().string(not(containsString(CREDENTIAL_SECRET))));

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userSession.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        mvc.perform(get("/api/v1/admin/users/{id}", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCodexAccountId").value(accountId.toString()));

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"%s\"}".formatted(userSession.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_INVALID"));

        AuthResponse replacementSession = authService.login("member@example.com", PASSWORD);
        mvc.perform(get("/api/v1/client/account/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(replacementSession.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(true))
                .andExpect(jsonPath("$.user.id").value(member.getId().toString()))
                .andExpect(jsonPath("$.codexAccount.id").value(accountId.toString()))
                .andExpect(jsonPath("$.codexAccount.displayName")
                        .value("Managed quota account"))
                .andExpect(jsonPath("$.codexAccount.status").value("ACTIVE"))
                .andExpect(jsonPath("$.codexAccount.email").doesNotExist())
                .andExpect(jsonPath("$.codexAccount.credential").doesNotExist())
                .andExpect(content().string(not(containsString(CREDENTIAL_SECRET))));

        mvc.perform(delete("/api/v1/admin/users/{id}/codex-account", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/admin/users/{id}", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignedCodexAccountId").value(nullValue()));

        mvc.perform(get("/api/v1/client/account/overview")
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer(replacementSession.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        AuthResponse afterUnassignment = authService.login("member@example.com", PASSWORD);
        mvc.perform(get("/api/v1/client/account/overview")
                        .header(HttpHeaders.AUTHORIZATION, bearer(afterUnassignment.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assigned").value(false))
                .andExpect(jsonPath("$.codexAccount").value(nullValue()));
    }

    @Test
    void onlyAdministratorsCanChangeAssignmentsAndInactiveAccountsAreRejected()
            throws Exception {
        User administrator = createAdministrator();
        AuthResponse memberSession = authService.register(
                "member@example.com",
                PASSWORD,
                "Member"
        );
        User member = users.findByEmail("member@example.com").orElseThrow();
        UUID inactiveAccountId = createAccount(administrator, "Unavailable", "DISABLED");
        String requestBody = "{\"codexAccountId\":\"%s\"}".formatted(inactiveAccountId);

        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberSession.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODEX_ACCOUNT_UNAVAILABLE"));

        mvc.perform(put("/api/v1/admin/users/{id}/codex-account", member.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"codexAccountId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CODEX_ACCOUNT_NOT_FOUND"));
    }

    private User createAdministrator() {
        authService.register("admin@example.com", PASSWORD, "Administrator");
        User administrator = users.findByEmail("admin@example.com").orElseThrow();
        administrator.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        return users.saveAndFlush(administrator);
    }

    private UUID createManagedAccount(User administrator, String displayName) throws Exception {
        return createAccount(administrator, displayName, "ACTIVE");
    }

    private UUID createAccount(User administrator, String displayName, String status)
            throws Exception {
        String location = mvc.perform(post("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"managed@example.com",
                                  "displayName":"%s",
                                  "status":"%s",
                                  "credential":{"access_token":"%s"}
                                }
                                """.formatted(displayName, status, CREDENTIAL_SECRET)))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);
        return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    }

    private String bearer(User user) {
        return bearer(jwtService.create(
                user.getId(),
                user.getRole().name(),
                user.getSessionVersion()
        ));
    }

    private String bearer(String accessToken) {
        return "Bearer " + accessToken;
    }
}
