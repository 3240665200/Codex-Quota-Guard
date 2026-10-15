package com.pingche.web.sessionbinding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.meter.MeterDevice;
import com.pingche.web.meter.MeterDeviceRepository;
import com.pingche.web.officialsession.*;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:session-binding;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionBindingIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T01:00:00Z");
    @Autowired SessionBindingService service;
    @Autowired OfficialAccountRepository accounts;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired MeterDeviceRepository devices;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired JwtService jwt;
    @MockitoBean OfficialConnectionService official;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private User user;
    private UUID accountId;
    private String accountKey;
    private UUID enrollmentId;
    private UUID deviceId;

    @BeforeEach void setup() {
        now.set(NOW);
        when(clock.instant()).thenAnswer(call -> now.get());
        accountId = accounts.saveAndFlush(new OfficialAccount("Binding " + UUID.randomUUID(), 1000, null, NOW)).getId();
        accountKey = "account-" + accountId;
        Team team = new Team("Binding " + UUID.randomUUID(), "", Team.Status.ACTIVE, 100, 100, NOW);
        team.updateTokenLimits(null, null, null, accountId, NOW);
        team = teams.saveAndFlush(team);
        user = users.saveAndFlush(new User(UUID.randomUUID() + "@example.com", "unused-synthetic-hash", "Binding user"));
        user.updateQuotaPolicy(team.getId(), null, null, NOW);
        user = users.saveAndFlush(user);
        enrollmentId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        devices.saveAndFlush(new MeterDevice(enrollmentId, user.getId(), UUID.randomUUID().toString().replace("-", "").repeat(2), NOW, NOW.plusSeconds(3600)));
        jdbc.update("insert into official_account_connections (official_account_id,requested_account_id,authorization_cipher,bound_account_id,bound_user_id,status,revision,imported_at,version) values (?,?,?,?,?,?,?,?,0)",
                accountId, accountKey, "synthetic-no-secret", accountKey, "official-user", "CONNECTED", UUID.randomUUID(), NOW);
        when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing"), true, "official-user"));
    }

    @Test void onlyFreshBeforeThenUniqueCodexCandidateAndUserConfirmationCanBind() {
        var attempt = create();
        assertThat(attempt.loginAllowed()).isFalse();
        attempt = service.prepare(user.getId(), attempt.id());
        assertThat(attempt.status()).isEqualTo("READY_FOR_LOGIN");
        assertThat(attempt.loginAllowed()).isTrue();
        assertThat(service.current(user.getId(), deviceId, enrollmentId).binding()).isNull();
        when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing", "new-codex"), true, "official-user"));
        attempt = service.detect(user.getId(), attempt.id());
        assertThat(attempt.status()).isEqualTo("AWAITING_CONFIRMATION");
        assertThat(attempt.candidateSessionId()).isEqualTo("new-codex");
        assertThat(service.current(user.getId(), deviceId, enrollmentId).binding()).isNull();
        attempt = service.confirm(user.getId(), attempt.id(), "new-codex");
        assertThat(attempt.status()).isEqualTo("BOUND");
        assertThat(attempt.binding().matchMethod()).isEqualTo("SERIAL_LOGIN_DIFF");
        assertThat(attempt.binding().verificationLevel()).isEqualTo("USER_CONFIRMED_INFERENCE");
        assertThat(service.bindings(accountId)).anySatisfy(binding -> assertThat(binding.userId()).isEqualTo(user.getId()));
    }

    @Test void queueIsDurableAndOnlyFirstAccountAttemptMayPrepare() {
        var first = create();
        var second = service.create(user.getId(), UUID.randomUUID(), enrollmentId, null);
        assertThat(second.queuePosition()).isEqualTo(2);
        assertThat(service.prepare(user.getId(), second.id()).status()).isEqualTo("QUEUED");
        assertThat(service.prepare(user.getId(), first.id()).loginAllowed()).isTrue();
        assertThat(jdbc.queryForObject("select active_attempt_id from session_binding_accounts where official_account_id=?", UUID.class, accountId)).isEqualTo(first.id());
    }

    @Test void duplicateCreateReturnsSamePersistedAttempt() {
        var first = create();
        assertThat(create().id()).isEqualTo(first.id());
    }

    @Test void failedBeforeNeverGrantsLoginAndDoesNotNeedRiskRecovery() {
        var attempt = create();
        when(official.refresh(accountId)).thenThrow(new DomainException("OFFICIAL_UNAVAILABLE", "unavailable", HttpStatus.BAD_GATEWAY));
        attempt = service.prepare(user.getId(), attempt.id());
        assertThat(attempt.status()).isEqualTo("REJECTED");
        assertThat(attempt.loginAllowed()).isFalse();
        assertThat(service.accountState(accountId).quarantined()).isFalse();
    }

    @Test void incompleteSnapshotCannotGrantLogin() {
        var attempt = create();
        when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing"), false, "official-user"));
        attempt = service.prepare(user.getId(), attempt.id());
        assertThat(attempt.errorCode()).isEqualTo("SESSION_BINDING_SNAPSHOT_INCOMPLETE");
        assertThat(attempt.loginAllowed()).isFalse();
    }

    @Test void noCandidateAndMultipleCandidatesNeverGuessAndQuarantineAccount() {
        for (List<String> ids : List.of(List.of("existing"), List.of("existing", "new-one", "new-two"))) {
            var attempt = create();
            service.prepare(user.getId(), attempt.id());
            when(official.refresh(accountId)).thenAnswer(call -> snapshot(ids, true, "official-user"));
            attempt = service.detect(user.getId(), attempt.id());
            assertThat(attempt.status()).isEqualTo("REJECTED");
            assertThat(attempt.errorCode()).isEqualTo(ids.size() == 1 ? "SESSION_BINDING_NO_NEW_SESSION" : "SESSION_BINDING_AMBIGUOUS");
            assertThat(attempt.binding()).isNull();
            assertThat(service.accountState(accountId).quarantined()).isTrue();
            service.recover(accountId, true);
            when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing"), true, "official-user"));
        }
    }

    @Test void changedOfficialIdentityCannotBind() {
        var attempt = create();
        service.prepare(user.getId(), attempt.id());
        when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing", "new"), true, "other-user"));
        attempt = service.detect(user.getId(), attempt.id());
        assertThat(attempt.errorCode()).isEqualTo("SESSION_BINDING_IDENTITY_CHANGED");
        assertThat(attempt.binding()).isNull();
    }

    @Test void browserOnlyNewSessionIsNotCodexCandidate() {
        var attempt = create();
        service.prepare(user.getId(), attempt.id());
        when(official.refresh(accountId)).thenAnswer(call -> new OfficialConnectionRefreshResponse(null,
                identity("official-user"), new OfficialSessions(List.of(session("existing", "Codex"), session("browser", "ChatGPT")), true), now.get()));
        assertThat(service.detect(user.getId(), attempt.id()).errorCode()).isEqualTo("SESSION_BINDING_NO_NEW_SESSION");
    }

    @Test void cancelAfterLoginPermissionFencesLateArrivalAndRequiresExplicitRecovery() {
        var first = create();
        service.prepare(user.getId(), first.id());
        var second = service.create(user.getId(), UUID.randomUUID(), enrollmentId, null);
        assertThat(service.cancel(user.getId(), first.id()).quarantined()).isTrue();
        assertThat(service.prepare(user.getId(), second.id()).loginAllowed()).isFalse();
        assertThatThrownBy(() -> service.recover(accountId, false)).isInstanceOf(DomainException.class);
        service.recover(accountId, true);
        assertThat(service.prepare(user.getId(), second.id()).loginAllowed()).isTrue();
    }

    @Test void expiredReadyAttemptCannotBecomeBaselineForNextUser() {
        var first = create();
        service.prepare(user.getId(), first.id());
        var second = service.create(user.getId(), UUID.randomUUID(), enrollmentId, null);
        now.set(NOW.plusSeconds(601));
        assertThat(service.get(user.getId(), first.id()).status()).isEqualTo("EXPIRED");
        assertThat(service.prepare(user.getId(), second.id()).quarantined()).isTrue();
    }

    @Test void candidateMustStillExistInFreshConfirmationSnapshot() {
        var attempt = create();
        service.prepare(user.getId(), attempt.id());
        when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing", "candidate"), true, "official-user"));
        service.detect(user.getId(), attempt.id());
        now.set(NOW.plusSeconds(61));
        when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing"), true, "official-user"));
        assertThat(service.confirm(user.getId(), attempt.id(), "candidate").status()).isEqualTo("REJECTED");
    }

    @Test void authorizationTeamOrEnrollmentChangesFailClosed() {
        for (String change : List.of("authorization", "team", "enrollment")) {
            var attempt = create();
            service.prepare(user.getId(), attempt.id());
            if (change.equals("authorization")) jdbc.update("update official_account_connections set revision=? where official_account_id=?", UUID.randomUUID(), accountId);
            if (change.equals("team")) jdbc.update("update users set team_id=null where id=?", user.getId());
            if (change.equals("enrollment")) jdbc.update("update meter_devices set status='REVOKED' where id=?", enrollmentId);
            attempt = service.get(user.getId(), attempt.id());
            assertThat(attempt.status()).isEqualTo("REJECTED");
            assertThat(attempt.loginAllowed()).isFalse();
            assertThat(attempt.quarantined()).isTrue();
            if (change.equals("team")) jdbc.update("update users set team_id=? where id=?", jdbc.queryForObject("select team_id from session_binding_attempts where id=?", UUID.class, attempt.id()), user.getId());
            if (change.equals("enrollment")) jdbc.update("update meter_devices set status='ACTIVE' where id=?", enrollmentId);
            service.recover(accountId, true);
        }
    }

    @Test void otherUserEnrollmentOrAccountAndForgedAttemptCannotBeUsed() {
        UUID outsider = users.saveAndFlush(new User(UUID.randomUUID()+"@example.com", "unused", "Outsider")).getId();
        assertThatThrownBy(() -> service.create(outsider, deviceId, enrollmentId, accountId)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> service.create(user.getId(), deviceId, enrollmentId, UUID.randomUUID())).isInstanceOf(DomainException.class);
        var attempt = create();
        assertThatThrownBy(() -> service.get(outsider, attempt.id())).isInstanceOf(DomainException.class);
    }

    @Test void releasePreservesHistoryAndActiveDeviceOrSessionCannotBeOverwritten() {
        var attempt = create();
        service.prepare(user.getId(), attempt.id());
        when(official.refresh(accountId)).thenAnswer(call -> snapshot(List.of("existing", "new"), true, "official-user"));
        service.detect(user.getId(), attempt.id());
        var bound = service.confirm(user.getId(), attempt.id(), "new").binding();
        assertThatThrownBy(() -> service.create(user.getId(), deviceId, enrollmentId, null)).isInstanceOf(DomainException.class);
        service.release(user.getId(), bound.id());
        assertThat(service.bindings(accountId)).anySatisfy(binding -> {
            assertThat(binding.id()).isEqualTo(bound.id());
            assertThat(binding.status()).isEqualTo("ENDED");
        });
        assertThat(create().status()).isEqualTo("QUEUED");
    }

    @Test void competingInstancesOnlyOneCanReserveBeforeAndCancellationFencesInFlightWork() throws Exception {
        var attempt = create();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        when(official.refresh(accountId)).thenAnswer(call -> {
            entered.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            return snapshot(List.of("existing"), true, "official-user");
        });
        CompletableFuture<SessionBindingAttemptResponse> pending = CompletableFuture.supplyAsync(() -> service.prepare(user.getId(), attempt.id()));
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(service.prepare(user.getId(), attempt.id()).status()).isEqualTo("PREPARING");
        service.cancel(user.getId(), attempt.id());
        finish.countDown();
        assertThat(pending.get(10, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
    }

    @Test void endpointsUseAuthenticatedUserAndRequireAdminForHistory() throws Exception {
        String token = "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion());
        mvc.perform(post("/api/v1/client/session-bindings/attempts").secure(true)
                .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"" + deviceId + "\",\"enrollmentId\":\"" + enrollmentId + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/client/session-bindings/attempts").secure(true).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"deviceId\":\"" + deviceId + "\",\"enrollmentId\":\"" + enrollmentId + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(get("/api/v1/admin/session-bindings").secure(true).header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isForbidden());
    }

    private SessionBindingAttemptResponse create() { return service.create(user.getId(), deviceId, enrollmentId, null); }
    private OfficialAccountInfo identity(String userKey) { return new OfficialAccountInfo(userKey, "synthetic@example.com", "Synthetic", accountKey, "Synthetic", "plus", "owner", false, true); }
    private OfficialConnectionRefreshResponse snapshot(List<String> ids, boolean complete, String userKey) {
        return new OfficialConnectionRefreshResponse(null, identity(userKey), new OfficialSessions(ids.stream().map(id -> session(id, "Codex")).toList(), complete), now.get());
    }
    private OfficialSessionInfo session(String id, String app) {
        return new OfficialSessionInfo(id, id, "windows", "Synthetic device", null, null, null, false, false, NOW, NOW, null, null, null, List.of(app));
    }
}
