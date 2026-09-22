package com.pingche.web.admin;

import static org.hamcrest.Matchers.containsString;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
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
class AdminIntegrationTest {

    private static final String PASSWORD = "admin-test-password";

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
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        sessions.deleteAll();
        users.deleteAll();
    }

    @Test
    void adminEndpointsRequireAuthenticationAndAdministratorRole() throws Exception {
        User administrator = createUser(
                "admin@example.com",
                "Administrator",
                User.Role.ADMIN,
                User.Status.ACTIVE
        );
        User ordinaryUser = createUser(
                "user@example.com",
                "Ordinary user",
                User.Role.USER,
                User.Status.ACTIVE
        );

        mvc.perform(get("/api/v1/admin/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mvc.perform(get("/api/v1/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinaryUser)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));

        mvc.perform(get("/api/v1/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk());
    }

    @Test
    void dashboardReturnsAllRequestedUserCounts() throws Exception {
        User administrator = createUser(
                "admin@example.com",
                "Administrator",
                User.Role.ADMIN,
                User.Status.ACTIVE
        );
        createUser(
                "active@example.com",
                "Active user",
                User.Role.USER,
                User.Status.ACTIVE
        );
        createUser(
                "disabled@example.com",
                "Disabled user",
                User.Role.USER,
                User.Status.DISABLED
        );
        User olderUser = createUser(
                "older@example.com",
                "Older user",
                User.Role.USER,
                User.Status.ACTIVE
        );
        setCreatedAt(olderUser, Instant.now().minus(8, ChronoUnit.DAYS));

        mvc.perform(get("/api/v1/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUsers").value(4))
                .andExpect(jsonPath("$.activeUsers").value(3))
                .andExpect(jsonPath("$.disabledUsers").value(1))
                .andExpect(jsonPath("$.adminUsers").value(1))
                .andExpect(jsonPath("$.newUsersLast7Days").value(3));
    }

    @Test
    void listsUsersWithPaginationAndCaseInsensitiveEmailOrDisplayNameFilters()
            throws Exception {
        User administrator = createUser(
                "admin@example.com",
                "Administrator",
                User.Role.ADMIN,
                User.Status.ACTIVE
        );
        User alice = createUser(
                "alice@example.com",
                "Alpha Person",
                User.Role.USER,
                User.Status.ACTIVE
        );
        User disabled = createUser(
                "disabled@example.com",
                "Buddy",
                User.Role.USER,
                User.Status.DISABLED
        );
        User newest = createUser(
                "newest@example.com",
                "Newest Person",
                User.Role.USER,
                User.Status.ACTIVE
        );
        Instant now = Instant.now();
        setCreatedAt(administrator, now.minus(4, ChronoUnit.HOURS));
        setCreatedAt(alice, now.minus(3, ChronoUnit.HOURS));
        setCreatedAt(disabled, now.minus(2, ChronoUnit.HOURS));
        setCreatedAt(newest, now.minus(1, ChronoUnit.HOURS));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("page", "0")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.items[0].email").value("newest@example.com"))
                .andExpect(jsonPath("$.items[1].email").value("disabled@example.com"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("page", "1")
                        .param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].email").value("alice@example.com"))
                .andExpect(jsonPath("$.items[1].email").value("admin@example.com"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("q", "ALICE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].email").value("alice@example.com"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("q", "bUdDy"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].email").value("disabled@example.com"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("status", "DISABLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].email").value("disabled@example.com"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].email").value("admin@example.com"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .param("status", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void administratorCanUpdateAnotherUserButCannotDisableOrDemoteSelf()
            throws Exception {
        User administrator = createUser(
                "admin@example.com",
                "Administrator",
                User.Role.ADMIN,
                User.Status.ACTIVE
        );
        User target = createUser(
                "target@example.com",
                "Target",
                User.Role.USER,
                User.Status.ACTIVE
        );
        User ordinaryUser = createUser(
                "ordinary@example.com",
                "Ordinary",
                User.Role.USER,
                User.Status.ACTIVE
        );

        mvc.perform(patch("/api/v1/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(ordinaryUser))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/v1/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(target.getId().toString()))
                .andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mvc.perform(patch("/api/v1/admin/users/{id}", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mvc.perform(patch("/api/v1/admin/users/{id}", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));

        mvc.perform(patch("/api/v1/admin/users/{id}", administrator.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SELF_PROTECTION"));

        mvc.perform(patch("/api/v1/admin/users/{id}", administrator.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SELF_PROTECTION"));
    }

    @Test
    void administratorCanCreateReadEditAndDeleteAUser() throws Exception {
        User administrator = createUser(
                "admin@example.com",
                "Administrator",
                User.Role.ADMIN,
                User.Status.ACTIVE
        );

        String location = mvc.perform(post("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"  MANAGED@example.com  ",
                                  "password":"managed-password-1",
                                  "displayName":"Managed user",
                                  "role":"USER",
                                  "status":"ACTIVE"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value("managed@example.com"))
                .andExpect(jsonPath("$.displayName").value("Managed user"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn()
                .getResponse()
                .getHeader(HttpHeaders.LOCATION);

        mvc.perform(get(location)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("managed@example.com"));

        mvc.perform(patch(location)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"renamed@example.com",
                                  "displayName":"Renamed user",
                                  "password":"replacement-password-2",
                                  "role":"ADMIN"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("renamed@example.com"))
                .andExpect(jsonPath("$.displayName").value("Renamed user"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        mvc.perform(delete(location)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isNoContent());

        mvc.perform(get(location)
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
    }

    @Test
    void administratorCannotCreateDuplicateUserOrDeleteSelf() throws Exception {
        User administrator = createUser(
                "admin@example.com",
                "Administrator",
                User.Role.ADMIN,
                User.Status.ACTIVE
        );

        mvc.perform(post("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "email":"ADMIN@example.com",
                                  "password":"managed-password-1",
                                  "displayName":"Duplicate"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_EXISTS"));

        mvc.perform(delete("/api/v1/admin/users/{id}", administrator.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ADMIN_SELF_PROTECTION"));
    }

    @Test
    void forceLogoutInvalidatesAccessAndRefreshTokensImmediately() throws Exception {
        User administrator = createUser(
                "admin@example.com",
                "Administrator",
                User.Role.ADMIN,
                User.Status.ACTIVE
        );
        var targetSession = authService.register(
                "target@example.com",
                PASSWORD,
                "Target"
        );
        User target = users.findByEmail("target@example.com").orElseThrow();

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + targetSession.accessToken()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/admin/users/{id}/force-logout", target.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(administrator)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + targetSession.accessToken()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"refreshToken":"%s"}
                                """.formatted(targetSession.refreshToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REFRESH_INVALID"));
    }

    private User createUser(
            String email,
            String displayName,
            User.Role role,
            User.Status status
    ) {
        authService.register(email, PASSWORD, displayName);
        User user = users.findByEmail(email).orElseThrow();
        user.updateAdministration(status, role);
        return users.saveAndFlush(user);
    }

    private void setCreatedAt(User user, Instant createdAt) {
        jdbcTemplate.update(
                "update users set created_at = ? where id = ?",
                Timestamp.from(createdAt),
                user.getId()
        );
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.create(user.getId(), user.getRole().name());
    }
}
