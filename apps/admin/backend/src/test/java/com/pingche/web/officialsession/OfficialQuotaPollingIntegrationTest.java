package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.quota.OfficialQuotaSnapshotRequest;
import com.pingche.web.quota.OfficialQuotaSnapshotResponse;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
    "spring.datasource.url=jdbc:h2:mem:official-quota-polling;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test") @AutoConfigureMockMvc
class OfficialQuotaPollingIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-25T04:00:00Z");
    @Autowired OfficialQuotaPollingService service;
    @Autowired OfficialAccountRepository accounts;
    @Autowired OfficialAccountConnectionRepository connections;
    @Autowired OfficialSessionCipher cipher;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @MockitoBean OfficialSessionClient client;
    @MockitoBean Clock clock;
    final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    OfficialAccount account;
    User admin;
    @BeforeEach void setup() {
        now.set(NOW); when(clock.instant()).thenAnswer(ignored -> now.get());
        users.deleteAll(); connections.deleteAll(); accounts.deleteAll();
        account = accounts.saveAndFlush(new OfficialAccount("Quota API account", 1000, null, NOW));
        admin = users.saveAndFlush(User.bootstrapAdministrator("admin@example.com", "synthetic-hash", "Admin"));
        var connection = new OfficialAccountConnection(account.getId());
        connection.requestedAccountId = "verified-account"; connection.boundAccountId = "verified-account"; connection.boundUserId = "verified-user";
        connection.status = "CONNECTED"; connection.revision = UUID.randomUUID(); connection.importedAt = NOW;
        connection.authorizationCipher = cipher.encrypt("Bearer synthetic-token", connection.aad("authorization"));
        connections.saveAndFlush(connection);
        when(client.fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any())).thenAnswer(ignored -> quota());
    }
    @Test void officialAccountListReadsCachedServerQuotaWithoutRequestingOfficialOnEveryPageRefresh() throws Exception {
        assertThat(service.refresh(account.getId()).quota().windows().get(0).usedPercent()).isEqualByComparingTo("27.5");
        for (int i = 0; i < 2; i++) mvc.perform(get("/api/v1/admin/official-accounts").header("Authorization", token()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].quota.windows[0].usedPercent").value(27.5))
                .andExpect(jsonPath("$[0].quotaPolling.status").value("SUCCESS"));
        verify(client, times(1)).fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any());
    }
    @Test void settingsAndRepeatedManualRefreshCannotBypassThePersistedCooldown() {
        service.configure(account.getId(), new OfficialQuotaPollingService.Settings(true, 60, 0L));
        service.refresh(account.getId());
        assertThatThrownBy(() -> service.refresh(account.getId())).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getCode()).isEqualTo("OFFICIAL_QUOTA_COOLDOWN"));
        service.configure(account.getId(), new OfficialQuotaPollingService.Settings(false, 60, 1L));
        assertThat(service.read(account.getId()).status()).isEqualTo("DISABLED");
        service.configure(account.getId(), new OfficialQuotaPollingService.Settings(true, 60, 2L));
        assertThat(service.read(account.getId()).status()).isEqualTo("SUCCESS");
        assertThatThrownBy(() -> service.refresh(account.getId())).isInstanceOf(DomainException.class);
        now.set(NOW.plusSeconds(61));
        service.refresh(account.getId());
        verify(client, times(2)).fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any());
    }
    @Test void rateLimitsHonorRetryAfterAndRetainThePreviousSuccessfulSnapshot() {
        service.refresh(account.getId());
        now.set(NOW.plusSeconds(301));
        when(client.fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any()))
                .thenThrow(new OfficialRateLimitedException("1800", now.get()));
        var response = service.refresh(account.getId());
        assertThat(response.status()).isEqualTo("RETRY_WAIT");
        assertThat(response.lastErrorCode()).isEqualTo("OFFICIAL_RATE_LIMITED");
        assertThat(response.quota()).isNotNull();
        assertThat(response.nextAttemptAt()).isAfterOrEqualTo(now.get().plusSeconds(1800));
        service.configure(account.getId(), new OfficialQuotaPollingService.Settings(true, 60, 0L));
        assertThat(service.read(account.getId()).nextAttemptAt()).isEqualTo(response.nextAttemptAt());
        assertThatThrownBy(() -> service.refresh(account.getId())).isInstanceOf(DomainException.class);
    }
    @Test void authFailuresPauseAutomaticRequestsUntilAuthorizationRevisionChanges() {
        when(client.fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any()))
                .thenThrow(new DomainException("OFFICIAL_AUTH_REQUIRED", "private-upstream-body", org.springframework.http.HttpStatus.UNAUTHORIZED));
        var response = service.refresh(account.getId());
        assertThat(response.status()).isEqualTo("AUTH_REQUIRED");
        now.set(NOW.plusSeconds(900));
        assertThat(service.dueAccounts()).isEmpty();
        assertThatThrownBy(() -> service.refresh(account.getId())).hasMessageContaining("更新授权");
        var connection = connections.findById(account.getId()).orElseThrow(); connection.revision = UUID.randomUUID(); connections.saveAndFlush(connection);
        assertThat(service.dueAccounts()).contains(account.getId());
        doReturn(quota()).when(client).fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any());
        assertThat(service.refresh(account.getId()).status()).isEqualTo("SUCCESS");
    }
    @Test void serializesConcurrentRequestsAndDiscardsOldAuthorizationResults() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        when(client.fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any())).thenAnswer(ignored -> {
            started.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException(); return quota();
        });
        CompletableFuture<OfficialQuotaPollingService.Response> pending = CompletableFuture.supplyAsync(() -> service.refresh(account.getId()));
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.refresh(account.getId())).isInstanceOf(DomainException.class);
            var connection = connections.findById(account.getId()).orElseThrow(); connection.revision = UUID.randomUUID(); connections.saveAndFlush(connection);
        } finally { release.countDown(); }
        assertThat(pending.get(10, TimeUnit.SECONDS).quota()).isNull();
        assertThat(service.read(account.getId()).status()).isEqualTo("PENDING");
        verify(client, times(1)).fetchQuota(any(), nullable(UUID.class), nullable(UUID.class), anyString(), any());
    }
    @Test void validatesIntervalsAndRecoversAnExpiredClaimAfterARestart() throws Exception {
        mvc.perform(put("/api/v1/admin/official-accounts/" + account.getId() + "/quota-polling").header("Authorization", token())
                .contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true,\"intervalSeconds\":1,\"version\":0}"))
                .andExpect(status().isBadRequest());
        service.configure(account.getId(), new OfficialQuotaPollingService.Settings(true, 300, 0L));
        jdbc.update("update official_account_quota_polls set attempt_id=?,lease_until=?,next_attempt_at=? where official_account_id=?",
                UUID.randomUUID(), Timestamp.from(NOW.minusSeconds(1)), Timestamp.from(NOW.minusSeconds(1)), account.getId());
        assertThat(service.dueAccounts()).contains(account.getId());
        service.refreshWhenDue(account.getId());
        assertThat(service.read(account.getId()).status()).isEqualTo("SUCCESS");
    }
    private OfficialQuotaSnapshotResponse quota() {
        return new OfficialQuotaSnapshotResponse("plus", List.of(new OfficialQuotaSnapshotRequest.Window("codex", "primary", 300,
                new BigDecimal("27.5"), NOW.plusSeconds(18000))), now.get(), now.get());
    }
    private String token() { return "Bearer " + jwt.create(admin.getId(), admin.getRole().name(), admin.getSessionVersion()); }
}
