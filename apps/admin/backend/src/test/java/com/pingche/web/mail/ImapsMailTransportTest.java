package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pingche.web.auth.DomainException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.doAnswer;
import org.mockito.MockedStatic;

class ImapsMailTransportTest {
    private final MailTargetPolicy targets = mock(MailTargetPolicy.class);
    private final MailBodyReader reader = mock(MailBodyReader.class);
    private final Session session = mock(Session.class);
    private final Store store = mock(Store.class);
    private final IMAPFolder folder = mock(IMAPFolder.class);
    private final IMAPFolder status = mock(IMAPFolder.class);
    private final ImapsMailTransport transport = new ImapsMailTransport(targets, reader);

    @BeforeEach void setup() throws Exception {
        when(targets.pinnedSocketFactory("imap.example.com", 993)).thenReturn(SocketFactory.getDefault());
        when(session.getStore("imaps")).thenReturn(store);
        when(store.getFolder("INBOX")).thenReturn(folder, status);
        when(folder.isOpen()).thenReturn(true);
        when(folder.getUIDValidity()).thenReturn(10L); when(folder.getUIDNext()).thenReturn(100L);
        when(status.getUIDValidity()).thenReturn(10L); when(status.getUIDNext()).thenReturn(102L);
    }

    @AfterEach void stopDeadlineThread() { transport.destroy(); }

    @Test void tlsHostnameReadOnlyPeekAndOnlyPostBaselineUidFetchAreEnforced() throws Exception {
        AtomicReference<Properties> settings = new AtomicReference<>();
        try (MockedStatic<Session> mocked = mockStatic(Session.class)) {
            mocked.when(() -> Session.getInstance(any(Properties.class))).thenAnswer(call -> {
                settings.set(call.getArgument(0)); return session;
            });
            Message message = mock(Message.class);
            when(folder.getMessagesByUID(100, 101)).thenReturn(new Message[] {message}); when(folder.getUID(message)).thenReturn(100L);
            when(reader.read(message)).thenReturn(new LoginMailMessage(List.of("sender@example.com"), List.of("owner@example.com"),
                    "ChatGPT Log-in Code", "synthetic-body", Instant.now()));
            try (MailTransport.Connection connection = transport.open(request(MailboxConfig.AuthMode.GOOGLE_OAUTH2))) {
                assertThat(connection.baseline()).isEqualTo(new MailTransport.Cursor(10, 100));
                MailTransport.Batch batch = connection.poll(100);
                assertThat(batch.nextUid()).isEqualTo(102); assertThat(batch.messages()).hasSize(1);
            }
            assertThat(settings.get()).containsEntry("mail.imaps.ssl.checkserveridentity", "true")
                    .containsEntry("mail.imaps.peek", "true").containsEntry("mail.imaps.auth.mechanisms", "XOAUTH2")
                    .containsEntry("mail.imaps.socketFactory.fallback", "false").doesNotContainKey("mail.imaps.ssl.trust");
            verify(folder).open(Folder.READ_ONLY); verify(folder).close(false); verify(store).close();
            verify(folder, never()).getMessages(); verify(session).setDebug(false);
        }
    }

    @Test void uidGapFetchUsesActualRangeEmptyPollReadsNoBodyAndMalformedMimeIsIgnored() throws Exception {
        try (MockedStatic<Session> mocked = mockStatic(Session.class)) {
            mocked.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            when(status.getUIDNext()).thenReturn(100L, 1000L);
            Message malformed = mock(Message.class);
            when(folder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[] {malformed}); when(folder.getUID(malformed)).thenReturn(100L);
            when(reader.read(malformed)).thenThrow(new IOException("synthetic-too-large"));
            try (MailTransport.Connection connection = transport.open(request(MailboxConfig.AuthMode.PASSWORD))) {
                assertThat(connection.poll(100).messages()).isEmpty();
                assertThat(connection.poll(100).nextUid()).isEqualTo(1000L);
            }
            verify(folder).getMessagesByUID(100, 999);
        }
    }

    @Test void sparseNewUidIsNotLostAndMoreThanFiftyNewMessagesFailClosedBeforeBodyRead() throws Exception {
        try (MockedStatic<Session> mocked = mockStatic(Session.class)) {
            mocked.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            when(status.getUIDNext()).thenReturn(10_001L);
            Message fresh = mock(Message.class);
            when(folder.getMessagesByUID(anyLong(), anyLong())).thenReturn(new Message[] {fresh}); when(folder.getUID(fresh)).thenReturn(10_000L);
            when(reader.read(fresh)).thenReturn(new LoginMailMessage(List.of("sender@example.com"), List.of("owner@example.com"),
                    "ChatGPT Log-in Code", "synthetic-body", Instant.now()));
            try (MailTransport.Connection connection = transport.open(request(MailboxConfig.AuthMode.PASSWORD))) {
                MailTransport.Batch batch = connection.poll(100);
                assertThat(batch.messages()).extracting(MailTransport.Candidate::uid).containsExactly(10_000L);
                assertThat(batch.nextUid()).isEqualTo(10_001L);
                when(folder.getMessagesByUID(100, 10_000)).thenReturn(new Message[51]);
                assertThatThrownBy(() -> connection.poll(100)).isInstanceOf(DomainException.class);
            }
        }
    }

    @Test void changedCursorOrLostConnectionFailsWithoutReconnectOrRawProviderErrors() throws Exception {
        try (MockedStatic<Session> mocked = mockStatic(Session.class)) {
            mocked.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            when(status.getUIDValidity()).thenReturn(11L);
            try (MailTransport.Connection connection = transport.open(request(MailboxConfig.AuthMode.PASSWORD))) {
                assertThatThrownBy(() -> connection.poll(100)).isInstanceOf(DomainException.class)
                        .hasMessage("Mailbox cursor changed; start a new listener");
                when(folder.isOpen()).thenReturn(false);
                assertThatThrownBy(() -> connection.poll(100)).isInstanceOf(DomainException.class)
                        .hasMessage("Mailbox connection failed; verify saved settings and authorization");
            }
            verify(store).connect("imap.example.com", 993, "owner@example.com", "synthetic-credential");
        }
    }

    @Test void missingBaselineAndAuthenticationFailureCloseResourcesWithFixedErrors() throws Exception {
        try (MockedStatic<Session> mocked = mockStatic(Session.class)) {
            mocked.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            when(folder.getUIDNext()).thenReturn(-1L);
            assertThatThrownBy(() -> transport.open(request(MailboxConfig.AuthMode.PASSWORD))).isInstanceOf(DomainException.class);
            doThrow(new MessagingException("private-secret provider detail")).when(store).connect(anyString(), eq(993), anyString(), anyString());
            assertThatThrownBy(() -> transport.open(request(MailboxConfig.AuthMode.PASSWORD)))
                    .isInstanceOf(DomainException.class).hasMessage("Mailbox connection failed; verify saved settings and authorization");
        }
    }

    @Test void connectingPhaseDeadlineClosesRawSocketBeforeAnyFolderExists() throws Exception {
        ScheduledExecutorService deadlines = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> task = mock(ScheduledFuture.class);
        AtomicReference<Runnable> expire = new AtomicReference<>();
        when(deadlines.schedule(any(Runnable.class), eq(20L), eq(TimeUnit.SECONDS))).thenAnswer(call -> {
            expire.set(call.getArgument(0)); return task;
        });
        MailTargetPolicy.PinnedSocketFactory factory = new MailTargetPolicy.PinnedSocketFactory(java.net.InetAddress.getByName("8.8.8.8"), 993);
        when(targets.pinnedSocketFactory("imap.example.com", 993)).thenReturn(factory);
        AtomicReference<java.net.Socket> raw = new AtomicReference<>();
        doAnswer(call -> { raw.set(factory.createSocket()); expire.get().run(); throw new MessagingException("deadline"); })
                .when(store).connect(anyString(), eq(993), anyString(), anyString());
        try (MockedStatic<Session> mocked = mockStatic(Session.class)) {
            mocked.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
            assertThatThrownBy(() -> new ImapsMailTransport(targets, reader, deadlines).open(request(MailboxConfig.AuthMode.PASSWORD)))
                    .isInstanceOf(DomainException.class);
            assertThat(raw.get().isClosed()).isTrue(); verify(task).cancel(false);
        }
    }

    private MailTransport.Settings request(MailboxConfig.AuthMode mode) {
        return new MailTransport.Settings("imap.example.com", 993, "owner@example.com", "INBOX", mode, "synthetic-credential");
    }
}
