package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import javax.net.SocketFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ImapsMailTransport implements MailTransport, DisposableBean {
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
            Folder selected = store.getFolder(settings.folder());
            if (!(selected instanceof IMAPFolder imap)) throw new MessagingException();
            folder = imap;
            folder.open(Folder.READ_ONLY);
            if (folder.getUIDNotSticky()) throw new MessagingException();
            IMAPFolder statusFolder = (IMAPFolder) store.getFolder(settings.folder());
            ImapsConnection connection = new ImapsConnection(store, folder, statusFolder, socketFactory.get());
            if (expired.get()) throw new MessagingException();
            return connection;
        } catch (MessagingException | RuntimeException ignored) {
            MailTargetPolicy.closeSockets(socketFactory.get());
            closeQuietly(store, folder);
            throw failure();
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
        return new DomainException("MAIL_CONNECTION_FAILED", "Mailbox connection failed; verify saved settings and authorization",
                HttpStatus.BAD_GATEWAY);
    }
    @Override public void destroy() { deadlines.shutdownNow(); }
}
