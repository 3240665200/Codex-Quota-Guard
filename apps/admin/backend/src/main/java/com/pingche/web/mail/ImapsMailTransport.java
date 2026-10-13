package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.net.SocketFactory;
import javax.net.ssl.SSLException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ImapsMailTransport implements MailTransport, DisposableBean {
    private enum ConnectionPhase { CONNECTING, FOLDER, CURSOR }
    private static final int MAX_ERROR_CAUSES = 32;
    private static final Set<String> NETEASE_HOSTS = Set.of("imap.163.com", "imap.126.com", "imap.yeah.net");
    private final MailTargetPolicy targets;
    private final MailBodyReader reader;
    private final ScheduledExecutorService deadlines;
    @Autowired
    public ImapsMailTransport(MailTargetPolicy targets, MailBodyReader reader) {
        this(targets, reader, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mail-connection-deadline"); thread.setDaemon(true); return thread;
        }));
    }
    ImapsMailTransport(MailTargetPolicy targets, MailBodyReader reader, ScheduledExecutorService deadlines) {
        this.targets = targets; this.reader = reader; this.deadlines = deadlines;
    }

    @Override public Connection open(Settings settings) {
        Store store = null;
        IMAPFolder folder = null;
        ConnectionPhase phase = ConnectionPhase.CONNECTING;
        AtomicReference<SocketFactory> socketFactory = new AtomicReference<>();
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> deadline = deadlines.schedule(() -> {
            expired.set(true); MailTargetPolicy.closeSockets(socketFactory.get());
        }, 20, TimeUnit.SECONDS);
        try {
            Properties props = new Properties();
            props.put("mail.imaps.ssl.enable", "true");
            props.put("mail.imaps.ssl.checkserveridentity", "true");
            props.put("mail.imaps.ssl.protocols", "TLSv1.3 TLSv1.2");
            props.put("mail.imaps.connectiontimeout", "10000");
            props.put("mail.imaps.timeout", "10000");
            props.put("mail.imaps.writetimeout", "10000");
            props.put("mail.imaps.peek", "true");
            props.put("mail.imaps.partialfetch", "true");
            props.put("mail.imaps.fetchsize", "16384");
            props.put("mail.imaps.statuscachetimeout", "0");
            socketFactory.set(targets.pinnedSocketFactory(settings.host(), settings.port()));
            if (expired.get()) throw new MessagingException();
            props.put("mail.imaps.socketFactory", socketFactory.get());
            props.put("mail.imaps.socketFactory.fallback", "false");
            if (settings.authMode() != MailboxConfig.AuthMode.PASSWORD) {
                props.put("mail.imaps.auth.mechanisms", "XOAUTH2");
                props.put("mail.imaps.auth.login.disable", "true");
                props.put("mail.imaps.auth.plain.disable", "true");
            }
            Session session = Session.getInstance(props);
            session.setDebug(false);
            store = session.getStore("imaps");
            store.connect(settings.host(), settings.port(), settings.username(), settings.credential());
            if (NETEASE_HOSTS.contains(settings.host().toLowerCase(Locale.ROOT))
                    && store instanceof IMAPStore imapStore && imapStore.hasCapability("ID"))
                imapStore.id(Map.of("name", "Codex Quota Guard"));
            phase = ConnectionPhase.FOLDER;
            Folder selected = store.getFolder(settings.folder());
            if (!(selected instanceof IMAPFolder imap)) throw new MessagingException();
            folder = imap;
            folder.open(Folder.READ_ONLY);
            phase = ConnectionPhase.CURSOR;
            if (folder.getUIDNotSticky()) throw new MessagingException();
            IMAPFolder statusFolder = (IMAPFolder) store.getFolder(settings.folder());
            ImapsConnection connection = new ImapsConnection(store, folder, statusFolder, socketFactory.get());
            if (expired.get()) throw new MessagingException();
            return connection;
        } catch (MessagingException | RuntimeException error) {
            DomainException safeFailure = connectionFailure(error, phase, expired.get());
            MailTargetPolicy.closeSockets(socketFactory.get());
            closeQuietly(store, folder);
            throw safeFailure;
        } finally { deadline.cancel(false); }
    }

    private final class ImapsConnection implements Connection {
        private final Store store;
        private final IMAPFolder folder;
        private final IMAPFolder statusFolder;
        private final Cursor baseline;
        private final SocketFactory socketFactory;
        ImapsConnection(Store store, IMAPFolder folder, IMAPFolder statusFolder, SocketFactory socketFactory) throws MessagingException {
            this.store = store; this.folder = folder; this.statusFolder = statusFolder;
            this.socketFactory = socketFactory;
            baseline = new Cursor(folder.getUIDValidity(), folder.getUIDNext());
            if (baseline.uidValidity() <= 0 || baseline.nextUid() <= 0) throw new MessagingException();
        }
        @Override public Cursor baseline() { return baseline; }
        @Override public Batch poll(long nextUid) {
            try {
                if (!folder.isOpen()) throw new MessagingException();
                long validity = statusFolder.getUIDValidity();
                long currentNext = statusFolder.getUIDNext();
                if (validity != baseline.uidValidity() || currentNext < nextUid) throw new DomainException(
                        "MAIL_CURSOR_CHANGED", "Mailbox cursor changed; start a new listener", HttpStatus.CONFLICT);
                if (currentNext == nextUid) return new Batch(validity, nextUid, List.of());
                // UIDs need not be contiguous. Read the actual post-baseline range, then cap body processing.
                long end = currentNext - 1;
                Message[] messages = folder.getMessagesByUID(nextUid, end);
                if (messages == null || messages.length > 50) throw new MessagingException();
                List<Candidate> candidates = new ArrayList<>();
                for (Message message : messages) {
                    long uid = folder.getUID(message);
                    if (uid < nextUid || uid > end) continue;
                    try {
                        LoginMailMessage parsed = reader.read(message);
                        if (parsed != null) candidates.add(new Candidate(uid, parsed));
                    } catch (IOException ignored) { /* Oversized/unsupported MIME is ignored without reading attachments. */ }
                }
                if (statusFolder.getUIDValidity() != validity) throw new MessagingException();
                return new Batch(validity, end + 1, List.copyOf(candidates));
            } catch (MessagingException ignored) { throw failure(); }
        }
        @Override public void close() {
            // Closing the raw sockets first interrupts FETCH/read before acquiring Jakarta Mail's folder locks.
            MailTargetPolicy.closeSockets(socketFactory); closeQuietly(store, folder);
        }
    }

    private void closeQuietly(Store store, IMAPFolder folder) {
        try { if (folder != null && folder.isOpen()) folder.close(false); } catch (MessagingException | RuntimeException ignored) { }
        try { if (store != null) store.close(); } catch (MessagingException | RuntimeException ignored) { }
    }
    private DomainException failure() {
        return failure("MAIL_CONNECTION_FAILED");
    }
    private DomainException connectionFailure(Throwable error, ConnectionPhase phase, boolean expired) {
        if (expired) return failure("MAIL_CONNECTION_TIMEOUT");
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(error);
        while (!pending.isEmpty() && seen.size() < MAX_ERROR_CAUSES) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) continue;
            if (current instanceof DomainException domain
                    && ("MAIL_DNS_FAILED".equals(domain.getCode()) || "MAIL_HOST_BLOCKED".equals(domain.getCode())))
                return failure(domain.getCode());
            if (current instanceof AuthenticationFailedException) return failure("MAIL_AUTH_FAILED");
            if (current instanceof SSLException || current instanceof CertificateException
                    || current instanceof CertPathValidatorException || current instanceof CertPathBuilderException)
                return failure("MAIL_TLS_FAILED");
            if (current instanceof SocketTimeoutException || current instanceof HttpTimeoutException)
                return failure("MAIL_CONNECTION_TIMEOUT");
            if (current.getCause() != null) pending.addLast(current.getCause());
            if (current instanceof MessagingException messaging && messaging.getNextException() != null)
                pending.addLast(messaging.getNextException());
        }
        return failure(switch (phase) {
            case CONNECTING -> "MAIL_CONNECTION_FAILED";
            case FOLDER -> "MAIL_FOLDER_FAILED";
            case CURSOR -> "MAIL_CURSOR_UNAVAILABLE";
        });
    }
    private DomainException failure(String code) {
        String message = switch (code) {
            case "MAIL_DNS_FAILED" -> "Mailbox server DNS resolution failed";
            case "MAIL_HOST_BLOCKED" -> "Mailbox host must be a public IMAPS server";
            case "MAIL_AUTH_FAILED" -> "Mailbox authentication failed";
            case "MAIL_TLS_FAILED" -> "Mailbox TLS verification failed";
            case "MAIL_CONNECTION_TIMEOUT" -> "Mailbox connection timed out";
            case "MAIL_FOLDER_FAILED" -> "Mailbox folder could not be opened";
            case "MAIL_CURSOR_UNAVAILABLE" -> "Mailbox UID cursor is unavailable";
            default -> "Mailbox connection failed; verify saved settings and authorization";
        };
        return new DomainException(code, message,
                "MAIL_HOST_BLOCKED".equals(code) ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY);
    }
    @Override public void destroy() { deadlines.shutdownNow(); }
}
