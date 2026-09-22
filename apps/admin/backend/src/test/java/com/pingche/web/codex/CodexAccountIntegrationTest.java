package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthService;
import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CodexAccountIntegrationTest {

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

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        accounts.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
    }

    @Test
    void administratorCanCreateListReadUpdateAndDeleteAnEncryptedAccount()
            throws Exception {
        User administrator = createAdministrator();
        String authorization = bearer(administrator);

        String location = mvc.perform(post("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"  CODEX@example.com  ",
                                  "displayName":"Primary Codex",
                                  "status":"ACTIVE",
                                  "credential":{
                                    "access_token":"%s",
                                    "refresh_token":"another-private-value"
                                  }
                                }
                                """.formatted(CREDENTIAL_SECRET)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("codex@example.com"))
                .andExpect(jsonPath("$.displayName").value("Primary Codex"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.hasCredential").value(true))
                .andExpect(jsonPath("$.credential").doesNotExist())
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        String ciphertext = jdbcTemplate.queryForObject(
                "select credential_ciphertext from codex_accounts where email = ?",
                String.class,
                "codex@example.com"
        );
        assertThat(ciphertext).doesNotContain(CREDENTIAL_SECRET);

        mvc.perform(get(location)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("codex@example.com"))
                .andExpect(jsonPath("$.credential").doesNotExist());

        mvc.perform(get("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .param("q", "PRIMARY")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].email").value("codex@example.com"));

        mvc.perform(patch(location)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"renamed@example.com",
                                  "displayName":"Renamed Codex",
                                  "status":"DISABLED",
                                  "credential":{"access_token":"replacement-secret"}
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("renamed@example.com"))
                .andExpect(jsonPath("$.displayName").value("Renamed Codex"))
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mvc.perform(delete(location)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isNoContent());

        mvc.perform(get(location)
                        .header(HttpHeaders.AUTHORIZATION, authorization))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CODEX_ACCOUNT_NOT_FOUND"));
    }

    @Test
    void rejectsDuplicateAccountsAndNonAdministratorAccess() throws Exception {
        User administrator = createAdministrator();
        authService.register("ordinary@example.com", PASSWORD, "Ordinary");
        User ordinary = users.findByEmail("ordinary@example.com").orElseThrow();
        String body = """
                {
                  "email":"duplicate@example.com",
                  "displayName":"Duplicate",
                  "credential":{"access_token":"secret"}
                }
                """;

        mvc.perform(post("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinary))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        mvc.perform(post("/api/v1/admin/codex-accounts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODEX_ACCOUNT_EMAIL_EXISTS"));
    }

    private User createAdministrator() {
        authService.register("admin@example.com", PASSWORD, "Administrator");
        User administrator = users.findByEmail("admin@example.com").orElseThrow();
        administrator.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        return users.saveAndFlush(administrator);
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.create(user.getId(), user.getRole().name());
    }
}
