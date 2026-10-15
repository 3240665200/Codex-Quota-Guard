package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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
import com.pingche.web.auth.SystemLogoutEvent;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "MAIL_ASSISTANCE_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:pingche-mail;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MailAssistanceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final String BODY = "ChatGPT Log-in Code\nWe noticed a suspicious log-in on your account. If that was you, enter this code:\n123456\nIf this wasn't you, reset your password.";
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired MailAssistanceService service;
    @Autowired MailboxConfigRepository mailboxes;
    @Autowired MailListenerRepository listeners;
    @Autowired OfficialAccountRepository accounts;
    @Autowired TeamRepository teams;
    @Autowired UserRepository users;
    @Autowired RefreshSessionRepository sessions;
    @Autowired JwtService jwt;
    @Autowired MockMvc mvc;
    @Autowired MailSecretCipher cipher;
    @MockitoBean MailTransport transport;
    @MockitoBean MailOAuthClient oauth;
    @MockitoBean Clock clock;
    private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
    private User owner;
    private User other;
    private User admin;
    private Team team;
    private OfficialAccount account;

    @BeforeEach
    void setup() {
        now.set(NOW);
        when(clock.instant()).thenAnswer(invocation -> now.get());
        listeners.deleteAll(); mailboxes.deleteAll(); sessions.deleteAll(); users.deleteAll(); teams.deleteAll(); accounts.deleteAll();
        account = accounts.saveAndFlush(new OfficialAccount("Mail test account", 0, null, NOW));
        team = new Team("Mail team", "", Team.Status.ACTIVE, 100, 100, NOW);
        team.updateTokenLimits(null, null, null, account.getId(), NOW); team = teams.saveAndFlush(team);
        owner = member("owner@example.com"); other = member("other@example.com");
        admin = users.saveAndFlush(User.bootstrapAdministrator("admin@example.com", "synthetic-unused-password-hash", "Admin"));
        when(transport.open(any())).thenAnswer(invocation -> new FakeConnection());
    }

    @AfterEach
    void stopJobs() {
        for (MailListener listener : listeners.findAll()) {
            try { service.cancel(listener.ownerUserId, listener.id); } catch (DomainException ignored) { }
        }
        await().atMost(Duration.ofSeconds(5)).until(() -> listeners.findAll().stream().noneMatch(MailListener::active));
    }

    @Test
    void configIsAdminOnlyEncryptedReadOnlyAndEmptySecretsArePreserved() throws Exception {
        mvc.perform(post("/api/v1/client/login-assistance/listeners").header(HttpHeaders.AUTHORIZATION, token(owner)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MAILBOX_UNCONFIGURED"));
        mvc.perform(get(configUrl()).header(HttpHeaders.AUTHORIZATION, token(owner))).andExpect(status().isForbidden());
        mvc.perform(get(configUrl()).header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.configured").value(false));
        mvc.perform(put(configUrl()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("synthetic-password")))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.hasPassword").value(true)).andExpect(jsonPath("$.password").doesNotExist());
        MailboxConfig stored = mailboxes.findById(account.getId()).orElseThrow();
        assertThat(stored.passwordCipher).doesNotContain("synthetic-password");
        String old = stored.passwordCipher;
        service.saveConfig(account.getId(), request("", MailboxConfig.AuthMode.PASSWORD));
        assertThat(mailboxes.findById(account.getId()).orElseThrow().passwordCipher).isEqualTo(old);
        mvc.perform(get("/api/v1/admin/mail-assistance/providers").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(15));
        FakeConnection connection = new FakeConnection();
        when(transport.open(any())).thenReturn(connection);
        mvc.perform(post(configUrl() + "/test").header(HttpHeaders.AUTHORIZATION, token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        assertThat(connection.polled.get()).isFalse(); assertThat(connection.closed.get()).isTrue();
        mvc.perform(delete(configUrl()).header(HttpHeaders.AUTHORIZATION, token(admin))).andExpect(status().isNoContent());
        assertThat(mailboxes.findById(account.getId())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"MAIL_DNS_FAILED", "MAIL_HOST_BLOCKED", "MAIL_AUTH_FAILED", "MAIL_TLS_FAILED",
            "MAIL_CONNECTION_TIMEOUT", "MAIL_FOLDER_FAILED"})
    void listenerPreservesSafeConnectionFailureCategoryAndReleasesMailboxForRetry(String code) throws Exception {
        configure();
        when(transport.open(any())).thenThrow(new DomainException(code, "private-provider-credential-details", HttpStatus.BAD_GATEWAY));
        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.FAILED);
        var response = mvc.perform(get("/api/v1/client/login-assistance/listeners/" + started.id())
                        .header(HttpHeaders.AUTHORIZATION, token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.errorCode").value(code))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("private-provider-credential-details", "synthetic-password");
        assertThat(listeners.findById(started.id()).orElseThrow().activeAccountId).isNull();
        doAnswer(invocation -> new FakeConnection()).when(transport).open(any());
        MailListenerResponse retry = service.start(owner.getId());
        assertThat(retry.id()).isNotEqualTo(started.id());
        awaitState(retry.id(), MailListener.State.LISTENING);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MAIL_UPSTREAM_PRIVATE_TEXT", "unexpected-private-provider-code"})
    void listenerMasksUnknownFailureCodesWithoutLeavingMailboxBusy(String code) throws Exception {
        configure();
        when(transport.open(any())).thenThrow(new DomainException(code, "private-provider-credential-details", HttpStatus.BAD_GATEWAY));
        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.FAILED);
        assertThat(service.poll(owner.getId(), started.id()).errorCode()).isEqualTo("MAIL_CONNECTION_FAILED");
        assertThat(listeners.findById(started.id()).orElseThrow().activeAccountId).isNull();
    }

    @Test
    void multipleSenderConfigurationIsCanonicalAndPreservesAuthorizationWhileInvalidatingListeners() throws Exception {
        configure();
        MailboxConfig previous = mailboxes.findById(account.getId()).orElseThrow();
        String previousSecret = previous.passwordCipher;
        UUID previousRevision = previous.revision;
        FakeConnection connection = new FakeConnection();
        when(transport.open(any())).thenReturn(connection);
        MailListenerResponse active = service.start(owner.getId());
        awaitState(active.id(), MailListener.State.LISTENING);

        mvc.perform(put(configUrl()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("", " Sender@Example.com ; second@example.com\r\nSENDER@example.com， SECOND@example.com；sender@example.com ")))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.senderEmail").value("sender@example.com,second@example.com"))
                .andExpect(jsonPath("$.hasPassword").value(true)).andExpect(jsonPath("$.password").doesNotExist());

        MailboxConfig saved = mailboxes.findById(account.getId()).orElseThrow();
        assertThat(saved.passwordCipher).isEqualTo(previousSecret);
        assertThat(saved.revision).isNotEqualTo(previousRevision);
        assertThat(saved.senderEmail).isEqualTo("sender@example.com,second@example.com");
        assertThat(listeners.findById(active.id()).orElseThrow().state).isEqualTo(MailListener.State.CANCELLED);
        await().atMost(Duration.ofSeconds(5)).untilTrue(connection.closed);
    }

    @Test
    void senderConfigurationRejectsInvalidAddressesAndLimitsWithoutEchoingInput() throws Exception {
        configure();
        String tooMany = IntStream.range(0, 3).mapToObj(index -> "sender" + index + "@example.com").collect(Collectors.joining(","));
        String tooLong = String.join(",", java.util.Collections.nCopies(5, "a".repeat(60) + "@example.com"));
        String[] invalidSenders = { null, "", " ", "example.com", "@example.com", "sender@", "*@example.com",
                "sender@*.example.com", "Display Name <sender@example.com>", "sender@example.com (comment)",
                "sender@example.com\t", "sender@example.com\u0000", "sender@example.com;not-an-address", tooMany, tooLong };
        for (String sender : invalidSenders) {
            var response = mvc.perform(put(configUrl()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                            .content(configJson("", sender)))
                    .andExpect(status().isBadRequest());
            if (sender != null && !sender.isBlank() && sender.length() <= 320)
                response.andExpect(jsonPath("$.code").value("MAIL_CONFIG_INVALID"))
                        .andExpect(jsonPath("$.message").value("Mailbox sender addresses are invalid"));
            assertThatThrownBy(() -> service.saveConfig(account.getId(), request("", MailboxConfig.AuthMode.PASSWORD, sender)))
                    .isInstanceOfSatisfying(DomainException.class, failure -> {
                        assertThat(failure.getCode()).isEqualTo("MAIL_CONFIG_INVALID");
                        assertThat(failure.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(failure.getMessage()).isEqualTo("Mailbox sender addresses are invalid");
                    });
            assertThat(mailboxes.findById(account.getId()).orElseThrow().senderEmail).isEqualTo("sender@example.com");
        }
    }

    @Test
    void senderConfigurationAcceptsTwoExactAddressesAndSingleAddressRemainsCompatible() throws Exception {
        String two = "sender@example.com,second@example.com";
        mvc.perform(put(configUrl()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("synthetic-password", two)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.senderEmail").value(two));
        mvc.perform(put(configUrl()).header(HttpHeaders.AUTHORIZATION, token(admin)).contentType(MediaType.APPLICATION_JSON)
                        .content(configJson("", " Sender@Example.com ")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.senderEmail").value("sender@example.com"))
                .andExpect(jsonPath("$.hasPassword").value(true));
    }

    @Test
    void oneNewCodeStopsImmediatelyCanBePolledAcrossDatabaseAndCanRepeat() throws Exception {
        configure();
        FakeConnection first = codeConnection(100, 10);
        FakeConnection second = codeConnection(100, 10);
        when(transport.open(any())).thenReturn(first, second);
        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.RECEIVED);
        await().atMost(Duration.ofSeconds(5)).untilTrue(first.closed);
        MailListenerResponse result = service.poll(owner.getId(), started.id());
        assertThat(result.code()).isEqualTo("123456"); assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(60));
        MailListener stored = listeners.findById(started.id()).orElseThrow();
        assertThat(stored.activeAccountId).isNull(); assertThat(stored.codeCipher).doesNotContain("123456");
        assertThat(cipher.decrypt(stored.codeCipher, stored.aad())).isEqualTo("123456");
        mvc.perform(get("/api/v1/client/login-assistance/listeners/{id}", started.id()).header(HttpHeaders.AUTHORIZATION, token(owner)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("123456"))
                .andExpect(jsonPath("$.body").doesNotExist()).andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.senders").doesNotExist()).andExpect(jsonPath("$.recipients").doesNotExist());
        MailListenerResponse repeated = service.start(owner.getId());
        awaitState(repeated.id(), MailListener.State.RECEIVED);
        assertThat(repeated.id()).isNotEqualTo(started.id());
    }

    @Test
    void chineseLoginMailPublishesOnlyTheCodeAndStopsItsListener() throws Exception {
        configure();
        FakeConnection connection = new FakeConnection();
        LoginMailMessage chinese = new LoginMailMessage(List.of("sender@example.com"), List.of("owner@example.com"),
                "你的临时 ChatGPT 登录代码", "请使用以下验证码完成登录：123456。如果不是你本人操作，请忽略。", NOW.plusSeconds(1));
        connection.batch.set(new MailTransport.Batch(10, 101, List.of(new MailTransport.Candidate(100, chinese))));
        when(transport.open(any())).thenReturn(connection);

        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.RECEIVED);
        await().atMost(Duration.ofSeconds(5)).untilTrue(connection.closed);
        mvc.perform(get("/api/v1/client/login-assistance/listeners/{id}", started.id()).header(HttpHeaders.AUTHORIZATION, token(owner)))
                .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.state").value("RECEIVED")).andExpect(jsonPath("$.code").value("123456"))
                .andExpect(jsonPath("$.body").doesNotExist()).andExpect(jsonPath("$.subject").doesNotExist())
                .andExpect(jsonPath("$.senders").doesNotExist()).andExpect(jsonPath("$.recipients").doesNotExist());
    }

    @Test
    void sharedAccountIsBusyAndAnotherUserCannotPollOrCancelOwnerResult() throws Exception {
        configure(); MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.LISTENING);
        mvc.perform(post("/api/v1/client/login-assistance/listeners").header(HttpHeaders.AUTHORIZATION, token(other)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MAIL_LISTENER_BUSY"));
        mvc.perform(get("/api/v1/client/login-assistance/listeners/{id}", started.id()).header(HttpHeaders.AUTHORIZATION, token(other)))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/client/login-assistance/listeners/{id}", started.id()).header(HttpHeaders.AUTHORIZATION, token(other)))
                .andExpect(status().isNotFound());
        assertThat(service.cancel(owner.getId(), started.id()).state()).isEqualTo(MailListener.State.CANCELLED);
        assertThat(listeners.findById(started.id()).orElseThrow().activeAccountId).isNull();
    }

    @Test
    void currentRestoresOnlyTheOwnerListenerWithoutOpeningAnotherConnectionOrResettingItsCursor() throws Exception {
        configure();
        assertThat(service.current(owner.getId()).listener()).isNull();
        FakeConnection connection = new FakeConnection();
        when(transport.open(any())).thenReturn(connection);
        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.LISTENING);
        MailListener before = listeners.findById(started.id()).orElseThrow();
        assertThat(before.expiresAt).isEqualTo(NOW.plusSeconds(600));
        for (int refresh = 0; refresh < 3; refresh++) {
            mvc.perform(get("/api/v1/client/login-assistance/listeners/current")
                            .header(HttpHeaders.AUTHORIZATION, token(owner)))
                    .andExpect(status().isOk()).andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(jsonPath("$.listener.id").value(started.id().toString()))
                    .andExpect(jsonPath("$.listener.state").value("LISTENING"));
        }
        assertThat(service.current(other.getId()).listener()).isNull();
        MailListener after = listeners.findById(started.id()).orElseThrow();
        assertThat(after.uidValidity).isEqualTo(before.uidValidity);
        assertThat(after.nextUid).isEqualTo(before.nextUid);
        assertThat(after.expiresAt).isEqualTo(before.expiresAt);
        assertThat(after.listeningAt).isEqualTo(before.listeningAt);
        org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(1)).open(any());
        assertThat(connection.closed).isFalse();
        service.cancel(owner.getId(), started.id());
        assertThat(service.current(owner.getId()).listener()).isNull();
    }

    @Test
    void currentRestoresUnexpiredReceivedCodeButNeverAnotherUsersCodeOrExpiredResult() throws Exception {
        configure();
        when(transport.open(any())).thenReturn(codeConnection(100, 10));
        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.RECEIVED);
        assertThat(service.current(owner.getId()).listener().code()).isEqualTo("123456");
        mvc.perform(get("/api/v1/client/login-assistance/listeners/current")
                        .header(HttpHeaders.AUTHORIZATION, token(other)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.listener").isEmpty());
        now.set(NOW.plusSeconds(60));
        assertThat(service.current(owner.getId()).listener()).isNull();
        assertThat(listeners.findById(started.id()).orElseThrow().codeCipher).isNull();
    }

    @Test
    void currentChecksCurrentMembershipAndConfigurationRevisionBeforeRestoring() {
        configure();
        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.LISTENING);
        owner.clearTeam(NOW); users.saveAndFlush(owner);
        assertThat(service.current(owner.getId()).listener()).isNull();
        assertThat(listeners.findById(started.id()).orElseThrow().state).isEqualTo(MailListener.State.FAILED);
        owner.updateQuotaPolicy(team.getId(), null, null, NOW); users.saveAndFlush(owner);
        MailListenerResponse changed = service.start(owner.getId());
        awaitState(changed.id(), MailListener.State.LISTENING);
        MailboxConfig config = mailboxes.findById(account.getId()).orElseThrow();
        config.revision = UUID.randomUUID(); mailboxes.saveAndFlush(config);
        assertThat(service.current(owner.getId()).listener()).isNull();
        assertThat(listeners.findById(changed.id()).orElseThrow().state).isEqualTo(MailListener.State.FAILED);
    }

    @Test
    void systemLogoutStopsOwnedListenerAndDoesNotAffectAnotherOwner() {
        configure();
        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.LISTENING);
        service.systemLogout(new SystemLogoutEvent(other.getId()));
        assertThat(service.current(owner.getId()).listener().id()).isEqualTo(started.id());
        service.systemLogout(new SystemLogoutEvent(owner.getId()));
        assertThat(service.current(owner.getId()).listener()).isNull();
        assertThat(listeners.findById(started.id()).orElseThrow().state).isEqualTo(MailListener.State.CANCELLED);
        assertThat(listeners.findById(started.id()).orElseThrow().activeAccountId).isNull();
    }

    @Test
    void acceptsNewUidReceivedBetweenBaselineAndListeningStatePublication() {
        configure();
        FakeConnection connection = new FakeConnection(() -> now.set(NOW.plusSeconds(2)));
        connection.batch.set(new MailTransport.Batch(10, 101, List.of(
                new MailTransport.Candidate(100, message(NOW.plusSeconds(1))))));
        when(transport.open(any())).thenReturn(connection);

        MailListenerResponse started = service.start(owner.getId());
        awaitState(started.id(), MailListener.State.RECEIVED);

        MailListenerResponse received = service.poll(owner.getId(), started.id());
        assertThat(received.startedAt()).isEqualTo(NOW);
        assertThat(received.listeningAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(received.code()).isEqualTo("123456");
        await().atMost(Duration.ofSeconds(5)).untilTrue(connection.closed);
    }

    @Test
    void rejectsOldUidAndOldInternalReceiptDateEvenIfTemplateMatches() {
        configure(); FakeConnection connection = new FakeConnection(() -> now.set(NOW.plusSeconds(2)));
        connection.batch.set(new MailTransport.Batch(10, 101, List.of(
                new MailTransport.Candidate(99, message(NOW.plusSeconds(3))),
                new MailTransport.Candidate(100, message(NOW.minusSeconds(1))))));
        when(transport.open(any())).thenReturn(connection);
        MailListenerResponse started = service.start(owner.getId());
        await().atMost(Duration.ofSeconds(5)).until(() -> connection.polled.get());
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(listeners.findById(started.id()).orElseThrow().nextUid).isEqualTo(101L));
        assertThat(service.poll(owner.getId(), started.id()).code()).isNull();
    }

    @Test
    void changedUidValidityFailsClosedAndClosesConnection() {
        configure(); FakeConnection connection = codeConnection(100, 11);
        when(transport.open(any())).thenReturn(connection);
        MailListenerResponse started = service.start(owner.getId()); awaitState(started.id(), MailListener.State.FAILED);
        assertThat(service.poll(owner.getId(), started.id()).errorCode()).isEqualTo("MAIL_CURSOR_CHANGED");
        assertThat(connection.closed.get()).isTrue();
    }

    @Test
    void leaseTimeoutAndResultExpiryReleaseSlotAndClearCiphertext() {
        configure(); MailListenerResponse active = service.start(owner.getId()); awaitState(active.id(), MailListener.State.LISTENING);
        now.set(NOW.plusSeconds(601));
        assertThat(service.poll(owner.getId(), active.id()).state()).isEqualTo(MailListener.State.TIMED_OUT);
        when(transport.open(any())).thenReturn(codeConnection(100, 10));
        MailListenerResponse received = service.start(owner.getId()); awaitState(received.id(), MailListener.State.RECEIVED);
        now.set(NOW.plusSeconds(662));
        MailListenerResponse expired = service.poll(owner.getId(), received.id());
        assertThat(expired.state()).isEqualTo(MailListener.State.TIMED_OUT); assertThat(expired.errorCode()).isEqualTo("CODE_EXPIRED");
        assertThat(expired.code()).isNull(); assertThat(expired.receivedAt()).isNull();
        assertThat(listeners.findById(received.id()).orElseThrow().codeCipher).isNull();
    }

    @Test
    void teamRevocationAndConfigChangesStopExistingListenersBeforeReturningCode() {
        configure(); MailListenerResponse revoked = service.start(owner.getId()); awaitState(revoked.id(), MailListener.State.LISTENING);
        owner.clearTeam(NOW); users.saveAndFlush(owner);
        assertThat(service.poll(owner.getId(), revoked.id()).errorCode()).isEqualTo("MAIL_ACCESS_REVOKED");
        owner.updateQuotaPolicy(team.getId(), null, null, NOW); users.saveAndFlush(owner);
        MailListenerResponse changed = service.start(owner.getId()); awaitState(changed.id(), MailListener.State.LISTENING);
        service.saveConfig(account.getId(), request("", MailboxConfig.AuthMode.PASSWORD));
        MailListenerResponse response = service.poll(owner.getId(), changed.id());
        assertThat(response.code()).isNull(); assertThat(response.errorCode()).isEqualTo("MAIL_ACCESS_REVOKED");
    }

    @Test
    void duplicateMailboxAndRecipientCannotBeAssignedToAnotherOfficialAccount() {
        configure(); OfficialAccount duplicate = accounts.saveAndFlush(new OfficialAccount("Duplicate", 0, null, NOW));
        assertThatThrownBy(() -> service.saveConfig(duplicate.getId(), request("password", MailboxConfig.AuthMode.PASSWORD)))
                .isInstanceOf(DomainException.class).hasMessage("Mailbox or recipient is already assigned");
    }

    @Test
    void changingTargetOrOAuthIdentityCannotReuseExistingCredentials() {
        configure();
        MailboxRequest changedTarget = new MailboxRequest(true, "CUSTOM", "imap.other.example.com", 993,
                "owner@example.com", "owner@example.com", "INBOX", "sender@example.com", MailboxConfig.AuthMode.PASSWORD,
                null, null, "", null, null);
        assertThatThrownBy(() -> service.saveConfig(account.getId(), changedTarget))
                .isInstanceOf(DomainException.class).hasMessage("Mailbox authorization is incomplete");
        assertThat(mailboxes.findById(account.getId()).orElseThrow().host).isEqualTo("imap.qq.com");
        MailboxRequest initialOAuth = new MailboxRequest(true, "OUTLOOK", "outlook.office365.com", 993,
                "owner@example.com", "owner@example.com", "INBOX", "sender@example.com", MailboxConfig.AuthMode.MICROSOFT_OAUTH2,
                "client-one", "common", null, "secret-one", "refresh-one");
        service.saveConfig(account.getId(), initialOAuth);
        MailboxRequest differentApp = new MailboxRequest(true, "OUTLOOK", "outlook.office365.com", 993,
                "owner@example.com", "owner@example.com", "INBOX", "sender@example.com", MailboxConfig.AuthMode.MICROSOFT_OAUTH2,
                "client-two", "common", null, "", "");
        assertThatThrownBy(() -> service.saveConfig(account.getId(), differentApp))
                .isInstanceOf(DomainException.class).hasMessage("Mailbox authorization is incomplete");
    }

    @Test
    void repeatedCancelAndStartIsRateLimitedAndRequiresCurrentTeam() {
        configure();
        for (int i = 0; i < 6; i++) {
            MailListenerResponse listener = service.start(owner.getId()); service.cancel(owner.getId(), listener.id());
        }
        assertThatThrownBy(() -> service.start(owner.getId())).isInstanceOf(DomainException.class)
                .hasMessage("Too many mailbox listener requests");
        owner.clearTeam(NOW); users.saveAndFlush(owner);
        assertThat(service.availability(owner.getId()).reasonCode()).isEqualTo("TEAM_UNAVAILABLE");
        assertThatThrownBy(() -> service.start(owner.getId())).isInstanceOf(DomainException.class);
    }

    @Test
    void oauthRefreshTokenIsEncryptedRotatedAndFixedToOfficialHost() {
        MailboxRequest request = new MailboxRequest(true, "OUTLOOK", "outlook.office365.com", 993,
                "owner@example.com", "owner@example.com", "INBOX", "sender@example.com", MailboxConfig.AuthMode.MICROSOFT_OAUTH2,
                "synthetic-client-id", "common", null, "synthetic-client-secret", "synthetic-refresh-token");
        service.saveConfig(account.getId(), request);
        when(oauth.refresh(any(), any(), any(), any(), any())).thenReturn(new MailOAuthClient.Token("synthetic-access-token", "synthetic-rotated-token"));
        assertThat(service.testConfig(account.getId()).success()).isTrue();
        MailboxConfig config = mailboxes.findById(account.getId()).orElseThrow();
        assertThat(cipher.decrypt(config.refreshTokenCipher, config.aad("refresh-token"))).isEqualTo("synthetic-rotated-token");
        assertThat(service.readConfig(account.getId()).hasPassword()).isFalse();
        assertThat(service.readConfig(account.getId()).hasRefreshToken()).isTrue();
    }

    @Test
    void cancelledOAuthRefreshAndNextStartSerializeAndReadRotatedCredentialFromDatabase() throws Exception {
        MailboxRequest request = new MailboxRequest(true, "OUTLOOK", "outlook.office365.com", 993,
                "owner@example.com", "owner@example.com", "INBOX", "sender@example.com", MailboxConfig.AuthMode.MICROSOFT_OAUTH2,
                "synthetic-client-id", "common", null, "synthetic-client-secret", "refresh-initial");
        service.saveConfig(account.getId(), request);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger(); List<String> tokens = new CopyOnWriteArrayList<>();
        when(oauth.refresh(any(), any(), any(), any(), any())).thenAnswer(call -> {
            int order = calls.incrementAndGet(); tokens.add(call.getArgument(3));
            if (order == 1) { entered.countDown(); assertThat(release.await(5, TimeUnit.SECONDS)).isTrue(); }
            return new MailOAuthClient.Token("synthetic-access-" + order, "refresh-rotated-" + order);
        });
        MailListenerResponse first = service.start(owner.getId()); assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(service.cancel(owner.getId(), first.id()).state()).isEqualTo(MailListener.State.CANCELLED);
        CompletableFuture<MailListenerResponse> next = CompletableFuture.supplyAsync(() -> service.start(owner.getId()));
        try {
            assertThatThrownBy(() -> next.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(calls.get()).isEqualTo(1);
        } finally { release.countDown(); }
        MailListenerResponse second = next.get(5, TimeUnit.SECONDS); awaitState(second.id(), MailListener.State.LISTENING);
        assertThat(tokens).containsExactly("refresh-initial", "refresh-rotated-1");
        MailboxConfig saved = mailboxes.findById(account.getId()).orElseThrow();
        assertThat(cipher.decrypt(saved.refreshTokenCipher, saved.aad("refresh-token"))).isEqualTo("refresh-rotated-2");
        assertThat(service.poll(owner.getId(), first.id()).code()).isNull();
    }

    @Test
    void connectionFailureIsGenericAndDoesNotReturnSensitiveException() {
        configure(); when(transport.open(any())).thenThrow(new IllegalStateException("private-secret raw provider response"));
        assertThat(service.testConfig(account.getId()).message()).doesNotContain("private-secret");
        MailListenerResponse listener = service.start(owner.getId()); awaitState(listener.id(), MailListener.State.FAILED);
        assertThat(service.poll(owner.getId(), listener.id()).errorCode()).isEqualTo("MAIL_CONNECTION_FAILED");
    }

    private User member(String email) {
        User user = new User(email, "synthetic-unused-password-hash", email);
        user.updateQuotaPolicy(team.getId(), null, null, NOW); return users.saveAndFlush(user);
    }
    private void configure() { service.saveConfig(account.getId(), request("synthetic-password", MailboxConfig.AuthMode.PASSWORD)); }
    private MailboxRequest request(String password, MailboxConfig.AuthMode mode) {
        return request(password, mode, "sender@example.com");
    }
    private MailboxRequest request(String password, MailboxConfig.AuthMode mode, String sender) {
        return new MailboxRequest(true, "QQ", "imap.qq.com", 993, "owner@example.com", "owner@example.com",
                "INBOX", sender, mode, null, null, password, null, null);
    }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }
    private String configUrl() { return "/api/v1/admin/official-accounts/" + account.getId() + "/mailbox"; }
    private String configJson(String password) {
        return configJson(password, "sender@example.com");
    }
    private String configJson(String password, String sender) {
        return """
                {"enabled":true,"provider":"QQ","host":"imap.qq.com","port":993,"username":"owner@example.com",
                 "recipientEmail":"owner@example.com","folder":"INBOX","senderEmail":%s,
                 "authMode":"PASSWORD","password":%s}
                """.formatted(JSON.writeValueAsString(sender), JSON.writeValueAsString(password));
    }
    private LoginMailMessage message(Instant received) { return new LoginMailMessage(List.of("sender@example.com"), List.of("owner@example.com"), "ChatGPT Log-in Code", BODY, received); }
    private FakeConnection codeConnection(long uid, long validity) {
        FakeConnection connection = new FakeConnection();
        connection.batch.set(new MailTransport.Batch(validity, 101, List.of(new MailTransport.Candidate(uid, message(now.get().plusSeconds(1))))));
        return connection;
    }
    private void awaitState(UUID id, MailListener.State expected) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(service.poll(owner.getId(), id).state()).isEqualTo(expected));
    }
    private static final class FakeConnection implements MailTransport.Connection {
        private final Runnable afterBaselineSnapshot;
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean polled = new AtomicBoolean();
        final AtomicReference<MailTransport.Batch> batch = new AtomicReference<>();
        FakeConnection() { this(() -> { }); }
        FakeConnection(Runnable afterBaselineSnapshot) { this.afterBaselineSnapshot = afterBaselineSnapshot; }
        @Override public MailTransport.Cursor baseline() {
            MailTransport.Cursor cursor = new MailTransport.Cursor(10, 100);
            afterBaselineSnapshot.run();
            return cursor;
        }
        @Override public MailTransport.Batch poll(long nextUid) {
            polled.set(true); MailTransport.Batch result = batch.getAndSet(null);
            return result == null ? new MailTransport.Batch(10, nextUid, List.of()) : result;
        }
        @Override public void close() { closed.set(true); }
    }
}
