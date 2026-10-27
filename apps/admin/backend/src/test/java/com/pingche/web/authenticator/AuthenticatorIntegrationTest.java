package com.pingche.web.authenticator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pingche.web.auth.JwtService;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import com.pingche.web.mail.AssistanceGrantFixture;
import com.pingche.web.mail.LoginAssistanceAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "OFFICIAL_SESSION_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "spring.datasource.url=jdbc:h2:mem:authenticator-tests;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc @ActiveProfiles("test")
class AuthenticatorIntegrationTest {
    @Autowired AuthenticatorService service;
    @Autowired OfficialAccountRepository accounts;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    OfficialAccount account;
    User owner, outsider, admin;
    UUID deviceId;
    static final Instant NOW = Instant.parse("2026-10-25T04:00:59Z");
    static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        users.deleteAll(); teams.deleteAll(); accounts.deleteAll();
        account = accounts.saveAndFlush(new OfficialAccount("TOTP account", 0, null, NOW));
        Team team = new Team("TOTP team", "", Team.Status.ACTIVE, 100, 100, NOW);
        team.updateTokenLimits(null, null, null, account.getId(), NOW); team = teams.saveAndFlush(team);
        owner = new User("owner@example.com", "synthetic-hash", "Owner");
        owner.updateQuotaPolicy(team.getId(), null, null, NOW); owner = users.saveAndFlush(owner);
        outsider = users.saveAndFlush(new User("outside@example.com", "synthetic-hash", "Outsider"));
        admin = users.saveAndFlush(User.bootstrapAdministrator("admin@example.com", "synthetic-hash", "Admin"));
        deviceId = AssistanceGrantFixture.grant(jdbc,owner.getId(),NOW);
    }
    @Test void storesOnlyAccountBoundCiphertextAndNeverReturnsTheSecret() throws Exception {
        String response = mvc.perform(put(configUrl()).header("Authorization", token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"secret\":\"" + SECRET + "\",\"version\":0}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.secret").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SECRET);
        String stored = jdbc.queryForObject("select secret_cipher from official_account_authenticators where official_account_id=?", String.class, account.getId());
        assertThat(stored).doesNotContain(SECRET);
        var saved = service.save(account.getId(), new AuthenticatorService.Request(false, "", 1L));
        assertThat(saved.enabled()).isFalse();
        assertThat(jdbc.queryForObject("select secret_cipher from official_account_authenticators where official_account_id=?", String.class, account.getId())).isEqualTo(stored);
        assertThat(new AuthenticatorService.Request(true, SECRET, 0L).toString()).doesNotContain(SECRET);
    }
    @Test void allowsOnlyTheGrantedDeviceBeforeBindingAndUsesServerTime() throws Exception {
        service.save(account.getId(), new AuthenticatorService.Request(true, SECRET, 0L));
        mvc.perform(get("/api/v1/client/login-assistance/authenticator").header("Authorization", token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,deviceId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
        mvc.perform(post("/api/v1/client/login-assistance/authenticator/code").header("Authorization", token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,deviceId))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").isString()).andExpect(jsonPath("$.expiresAt").value("2026-10-25T04:01:00Z"))
                .andExpect(jsonPath("$.serverTime").value(NOW.toString())).andExpect(jsonPath("$.secret").doesNotExist());
        mvc.perform(post("/api/v1/client/login-assistance/authenticator/code").header("Authorization", token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,deviceId)).andExpect(status().isTooManyRequests());
        mvc.perform(post("/api/v1/client/login-assistance/authenticator/code").header("Authorization", token(outsider))).andExpect(status().isForbidden());
        mvc.perform(get(configUrl()).header("Authorization", token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,deviceId)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/client/login-assistance/authenticator/code")).andExpect(status().isUnauthorized());
    }
    @Test void rejectsCiphertextCopiedFromAnotherAccountAndDisabledConfiguration() {
        service.save(account.getId(), new AuthenticatorService.Request(true, SECRET, 0L));
        OfficialAccount another = accounts.saveAndFlush(new OfficialAccount("Another", 0, null, NOW));
        service.save(another.getId(), new AuthenticatorService.Request(true, SECRET, 0L));
        String encrypted = jdbc.queryForObject("select secret_cipher from official_account_authenticators where official_account_id=?", String.class, another.getId());
        jdbc.update("update official_account_authenticators set secret_cipher=? where official_account_id=?", encrypted, account.getId());
        assertThatThrownBy(() -> service.code(owner.getId(), deviceId.toString())).hasMessageContaining("无法读取").hasMessageNotContaining(SECRET);
        service.save(account.getId(), new AuthenticatorService.Request(false, null, 1L));
        assertThat(service.availability(owner.getId(), deviceId.toString()).available()).isFalse();
        assertThatThrownBy(() -> service.code(owner.getId(), deviceId.toString())).hasMessageContaining("尚未启用");
    }
    @Test void refusesStaleAdminUpdatesAndRevokesCodeAccessAfterTeamRemoval() {
        service.save(account.getId(), new AuthenticatorService.Request(true, SECRET, 0L));
        assertThatThrownBy(() -> service.save(account.getId(), new AuthenticatorService.Request(true, SECRET, 0L))).hasMessageContaining("已变化");
        owner.updateQuotaPolicy(null, null, null, NOW); users.saveAndFlush(owner);
        assertThatThrownBy(() -> service.availability(owner.getId(), deviceId.toString()))
                .hasMessage(LoginAssistanceAccess.denied().getMessage());
        assertThatThrownBy(() -> service.code(owner.getId(), deviceId.toString()))
                .hasMessage(LoginAssistanceAccess.denied().getMessage());
    }
    @Test void administratorsCanPreviewWithoutATeamAndWithoutEnablingMemberAccess() throws Exception {
        service.save(account.getId(), new AuthenticatorService.Request(false, SECRET, 0L));
        mvc.perform(post(configUrl() + "/code").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.code").value(Totp.code(SECRET, "SHA1", 6, 30, NOW)))
                .andExpect(jsonPath("$.serverTime").value(NOW.toString()))
                .andExpect(jsonPath("$.expiresAt").value("2026-10-25T04:01:00Z"))
                .andExpect(jsonPath("$.officialAccountId").value(account.getId().toString()))
                .andExpect(jsonPath("$.secret").doesNotExist());
        assertThat(service.read(account.getId()).enabled()).isFalse();
        assertThat(service.availability(owner.getId(), deviceId.toString()).available()).isFalse();
        mvc.perform(post(configUrl() + "/code").header("Authorization", token(admin))).andExpect(status().isTooManyRequests());
    }
    @Test void previewRequiresAnAdministratorAndAnExistingSavedAccount() throws Exception {
        service.save(account.getId(), new AuthenticatorService.Request(true, SECRET, 0L));
        mvc.perform(post(configUrl() + "/code")).andExpect(status().isUnauthorized());
        mvc.perform(post(configUrl() + "/code").header("Authorization", token(owner)).header(LoginAssistanceAccess.DEVICE_HEADER,deviceId)).andExpect(status().isForbidden());
        mvc.perform(post(configUrl() + "/code").header("Authorization", token(outsider))).andExpect(status().isForbidden());
        assertThatThrownBy(() -> service.preview(account.getId(), owner.getId())).hasMessageContaining("仅管理员");
        mvc.perform(post("/api/v1/admin/official-accounts/" + UUID.randomUUID() + "/authenticator/code")
                .header("Authorization", token(admin))).andExpect(status().isNotFound());
        service.delete(account.getId());
        mvc.perform(post(configUrl() + "/code").header("Authorization", token(admin))).andExpect(status().isConflict());
    }
    @Test void previewUsesTheAccountBoundEncryptionAndNeverReturnsACopiedSecret() throws Exception {
        service.save(account.getId(), new AuthenticatorService.Request(true, SECRET, 0L));
        OfficialAccount another = accounts.saveAndFlush(new OfficialAccount("Another preview", 0, null, NOW));
        service.save(another.getId(), new AuthenticatorService.Request(true, SECRET, 0L));
        String encrypted = jdbc.queryForObject("select secret_cipher from official_account_authenticators where official_account_id=?", String.class, another.getId());
        jdbc.update("update official_account_authenticators set secret_cipher=? where official_account_id=?", encrypted, account.getId());
        String response = mvc.perform(post(configUrl() + "/code").header("Authorization", token(admin)))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AUTHENTICATOR_SECRET_UNAVAILABLE"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(SECRET).doesNotContain(encrypted);
    }
    private String configUrl() { return "/api/v1/admin/official-accounts/" + account.getId() + "/authenticator"; }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }
}
