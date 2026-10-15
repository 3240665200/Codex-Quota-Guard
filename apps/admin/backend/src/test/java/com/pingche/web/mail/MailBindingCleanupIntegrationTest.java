package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.pingche.web.auth.DomainException;
import com.pingche.web.meter.MeterDevice;
import com.pingche.web.meter.MeterDeviceRepository;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.officialsession.OfficialAccountInfo;
import com.pingche.web.officialsession.OfficialConnectionRefreshResponse;
import com.pingche.web.officialsession.OfficialSessionInfo;
import com.pingche.web.officialsession.OfficialSessions;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.sessionbinding.SessionBindingService;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {
        "MAIL_ASSISTANCE_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:mail-binding-cleanup;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class MailBindingCleanupIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    @Autowired MailAssistanceService mail;
    @Autowired SessionBindingService bindings;
    @Autowired MailListenerRepository listeners;
    @Autowired MailSecretCipher cipher;
    @Autowired OfficialAccountRepository accounts;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired MeterDeviceRepository devices;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean Clock clock;
    @MockitoBean MailTransport transport;
    @MockitoBean OfficialConnectionService official;
    private final List<FakeConnection> opened = new CopyOnWriteArrayList<>();
    private UUID accountId, deviceId, otherDeviceId;
    private User owner, other;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        accountId = accounts.saveAndFlush(new OfficialAccount("Synthetic binding mail " + UUID.randomUUID(), 0, null, NOW)).getId();
        Team team = new Team("Synthetic " + accountId, "", Team.Status.ACTIVE, 100, 100, NOW);
        team.updateTokenLimits(null, null, null, accountId, NOW); team = teams.saveAndFlush(team);
        owner = member(team.getId()); other = member(team.getId());
        deviceId = device(owner); otherDeviceId = device(other);
        String external = "synthetic-" + accountId;
        jdbc.update("""
                insert into official_account_connections
                (official_account_id,requested_account_id,authorization_cipher,bound_account_id,bound_user_id,status,revision,imported_at,version)
                values (?,?,?,?,?,'CONNECTED',?,?,0)
                """, accountId, external, "synthetic-no-secret", external, "synthetic-user", UUID.randomUUID(), NOW);
        String address = "synthetic-" + accountId + "@example.test";
        mail.saveConfig(accountId, new MailboxRequest(true, "QQ", "imap.qq.com", 993, address, address,
                "INBOX", "sender@example.test", MailboxConfig.AuthMode.PASSWORD, null, null, "synthetic-password", null, null));
        when(transport.open(any())).thenAnswer(call -> { var connection = new FakeConnection(); opened.add(connection); return connection; });
    }

    @AfterEach void cleanup() {
        for (MailListener listener : listeners.findByOfficialAccountId(accountId)) mail.cancel(listener.ownerUserId, listener.id);
        for (FakeConnection connection : opened) connection.close();
    }

    @ParameterizedTest
    @CsvSource({"PREPARING,cancel", "READY_FOR_LOGIN,cancel", "DETECTING,cancel", "AWAITING_CONFIRMATION,cancel",
            "READY_FOR_LOGIN,expire", "READY_FOR_LOGIN,fail"})
    void endingTheOwnedClaimReleasesItsMailboxAndClosesOnlyAfterCommit(String stage, String ending) {
        UUID attempt = claimed(stage);
        MailListenerResponse listener = start(owner);
        FakeConnection connection = opened.get(0);
        if (ending.equals("expire")) jdbc.update("update session_binding_attempts set expires_at=? where id=?", NOW.minusSeconds(1), attempt);
        if (ending.equals("fail")) when(official.refresh(accountId)).thenThrow(
                new DomainException("SESSION_BINDING_SNAPSHOT_UNAVAILABLE", "synthetic-provider-detail", HttpStatus.BAD_GATEWAY));
        if (ending.equals("cancel")) bindings.cancel(owner.getId(), attempt);
        else if (ending.equals("expire")) bindings.get(owner.getId(), attempt);
        else bindings.detect(owner.getId(), attempt);
        MailListener stopped = listeners.findById(listener.id()).orElseThrow();
        assertThat(stopped.state).isEqualTo(MailListener.State.CANCELLED);
        assertThat(stopped.errorCode).isEqualTo("MAIL_BINDING_ENDED");
        assertThat(stopped.activeAccountId).isNull();
        await().atMost(Duration.ofSeconds(5)).untilTrue(connection.closed);
        assertThat(mail.current(owner.getId()).listener()).isNull();
        assertThat(start(other).state()).isEqualTo(MailListener.State.LISTENING);
    }

    @Test void cancellingAnUnrelatedQueuedUserPreservesTheCurrentOwnersListener() {
        claimed("READY_FOR_LOGIN");
        MailListenerResponse listener = start(owner);
        UUID queued = bindings.create(other.getId(), otherDeviceId, otherDeviceId, null).id();
        bindings.cancel(other.getId(), queued);
        assertThat(mail.poll(owner.getId(), listener.id()).state()).isEqualTo(MailListener.State.LISTENING);
        assertThat(opened.get(0).closed).isFalse();
    }

    @Test void automaticBindingSuccessReleasesTheOwnersMailListener() {
        UUID attempt = claimed("READY_FOR_LOGIN");
        MailListenerResponse listener = start(owner);
        AtomicReference<Instant> time = new AtomicReference<>(NOW);
        when(clock.instant()).thenAnswer(call -> time.get());
        when(official.refresh(accountId)).thenAnswer(call -> new OfficialConnectionRefreshResponse(null,
                new OfficialAccountInfo("synthetic-user", null, null, "synthetic-" + accountId, "Synthetic", "plus", "owner", false, true),
                new OfficialSessions(List.of(new OfficialSessionInfo("new-session", "new-session", "Windows", "Synthetic", null, null, null, false, false, NOW, NOW, null, null, null, List.of("Codex"))), true), time.get()));
        bindings.advance(accountId);
        time.set(NOW.plusSeconds(10));bindings.advance(accountId);
        assertThat(bindings.get(owner.getId(), attempt).status()).isEqualTo("BOUND");
        assertThat(listeners.findById(listener.id()).orElseThrow().state).isEqualTo(MailListener.State.CANCELLED);
        assertThat(mail.current(owner.getId()).listener()).isNull();
        await().atMost(Duration.ofSeconds(5)).untilTrue(opened.get(0).closed);
        assertThat(bindings.accountState(accountId).activeAttemptId()).isNull();
    }

    @Test void endingAnOwnerClaimDoesNotCancelAnotherOwnersMailboxListener() {
        UUID attempt = claimed("READY_FOR_LOGIN");
        MailListenerResponse listener = start(other);
        bindings.cancel(owner.getId(), attempt);
        assertThat(mail.poll(other.getId(), listener.id()).state()).isEqualTo(MailListener.State.LISTENING);
        assertThat(opened.get(0).closed).isFalse();
    }

    @Test void cancellingTheSameOwnersOtherQueuedTaskPreservesTheirCurrentClaimAndListener() {
        UUID current = claimed("READY_FOR_LOGIN");
        MailListenerResponse listener = start(owner);
        UUID anotherDevice = device(owner);
        UUID queued = bindings.create(owner.getId(), anotherDevice, anotherDevice, null).id();
        bindings.cancel(owner.getId(), queued);
        assertThat(bindings.accountState(accountId).activeAttemptId()).isEqualTo(current);
        assertThat(mail.poll(owner.getId(), listener.id()).state()).isEqualTo(MailListener.State.LISTENING);
        assertThat(opened.get(0).closed).isFalse();
    }

    @Test void rollbackPreservesBothTheClaimAndItsLiveMailConnection() {
        UUID attempt = claimed("READY_FOR_LOGIN");
        MailListenerResponse listener = start(owner);
        FakeConnection connection = opened.get(0);
        new TransactionTemplate(transactionManager).execute(status -> {
            bindings.cancel(owner.getId(), attempt);
            assertThat(listeners.findById(listener.id()).orElseThrow().state).isEqualTo(MailListener.State.CANCELLED);
            assertThat(connection.closed).isFalse();
            status.setRollbackOnly();
            return null;
        });
        assertThat(bindings.get(owner.getId(), attempt).status()).isEqualTo("READY_FOR_LOGIN");
        assertThat(mail.poll(owner.getId(), listener.id()).state()).isEqualTo(MailListener.State.LISTENING);
        assertThat(connection.closed).isFalse();
    }

    @Test void theOldAfterCommitCallbackCannotCloseANewlyStartedListener() {
        UUID attempt = claimed("READY_FOR_LOGIN");
        MailListenerResponse old = start(owner);
        AtomicReference<MailListenerResponse> replacement = new AtomicReference<>();
        new TransactionTemplate(transactionManager).execute(status -> {
            // Run before the binding cleanup callback, on a thread without the completed transaction's resources.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    try { replacement.set(CompletableFuture.supplyAsync(() -> start(owner)).get(5, TimeUnit.SECONDS)); }
                    catch (Exception failure) { throw new IllegalStateException("Synthetic replacement listener failed", failure); }
                }
            });
            bindings.cancel(owner.getId(), attempt);
            return null;
        });
        assertThat(replacement.get().id()).isNotEqualTo(old.id());
        assertThat(mail.poll(owner.getId(), replacement.get().id()).state()).isEqualTo(MailListener.State.LISTENING);
        assertThat(opened.get(1).closed).isFalse();
    }

    @Test void endingTheClaimAlsoClearsAnUnexpiredReceivedCode() {
        UUID attempt = claimed("READY_FOR_LOGIN");
        MailListenerResponse started = start(owner);
        new TransactionTemplate(transactionManager).execute(status -> {
            MailListener listener = listeners.findByIdForUpdate(started.id()).orElseThrow();
            listener.state = MailListener.State.RECEIVED; listener.activeAccountId = null;
            listener.codeCipher = cipher.encrypt("123456", listener.aad()); listener.receivedAt = NOW;
            listener.resultExpiresAt = NOW.plusSeconds(60); listener.expiresAt = listener.resultExpiresAt;
            return null;
        });
        assertThat(mail.current(owner.getId()).listener().code()).isEqualTo("123456");
        bindings.cancel(owner.getId(), attempt);
        assertThat(mail.current(owner.getId()).listener()).isNull();
        assertThat(listeners.findById(started.id()).orElseThrow().codeCipher).isNull();
    }

    private UUID claimed(String stage) {
        UUID id = bindings.create(owner.getId(), deviceId, deviceId, null).id();
        jdbc.update("update session_binding_accounts set active_attempt_id=?,generation=1 where official_account_id=?", id, accountId);
        jdbc.update("update session_binding_attempts set status=?,claim_generation=1,before_session_ids='[]',expires_at=? where id=?",
                stage, NOW.plusSeconds(1800), id);
        return id;
    }
    private MailListenerResponse start(User user) {
        MailListenerResponse started = mail.start(user.getId());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(mail.poll(user.getId(), started.id()).state()).isEqualTo(MailListener.State.LISTENING));
        return mail.poll(user.getId(), started.id());
    }
    private User member(UUID teamId) {
        User user = new User(UUID.randomUUID() + "@example.test", "synthetic-unused", "Synthetic");
        user.updateQuotaPolicy(teamId, null, null, NOW); return users.saveAndFlush(user);
    }
    private UUID device(User user) {
        UUID id = UUID.randomUUID();
        return devices.saveAndFlush(new MeterDevice(id, user.getId(), UUID.randomUUID().toString().replace("-", "").repeat(2),
                NOW, NOW.plusSeconds(7200))).getId();
    }
    private static final class FakeConnection implements MailTransport.Connection {
        final AtomicBoolean closed = new AtomicBoolean();
        public MailTransport.Cursor baseline() { return new MailTransport.Cursor(10, 100); }
        public MailTransport.Batch poll(long next) { return new MailTransport.Batch(10, next, List.of()); }
        public void close() { closed.set(true); }
    }
}
