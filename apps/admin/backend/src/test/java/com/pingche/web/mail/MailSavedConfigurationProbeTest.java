package com.pingche.web.mail;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/** Explicit operator-approved probe. Reads one saved configuration, never mail bodies or writes. */
@EnabledIfEnvironmentVariable(named = "CQG_SAVED_MAILBOX_PROBE", matches = "APPROVED_READ_ONLY")
class MailSavedConfigurationProbeTest {
    @Test
    void opensOnlyTheApprovedSavedNeteaseInboxOnceWithoutReadingMailBodies() {
        String result = "PROBE_CONFIGURATION_UNAVAILABLE";
        boolean attempted = false;
        try {
            String label = required("CQG_MAIL_PROBE_LABEL");
            Properties database = new Properties();
            database.setProperty("user", required("DATABASE_USERNAME"));
            database.setProperty("password", required("DATABASE_PASSWORD"));
            database.setProperty("connectTimeout", "5");
            database.setProperty("socketTimeout", "10");
            MailTransport.Settings settings;
            try (var connection = DriverManager.getConnection(required("DATABASE_URL"), database)) {
                connection.setReadOnly(true);
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("""
                        select m.official_account_id, m.host, m.port, m.username, m.folder,
                               m.auth_mode, m.password_cipher, m.enabled, m.connection_test_until,
                               exists(select 1 from mail_login_listeners l
                                      where l.active_account_id = m.official_account_id
                                        and l.expires_at > current_timestamp) as listening
                        from official_account_mailboxes m
                        join official_accounts a on a.id = m.official_account_id
                        where lower(a.label) = lower(?)
                        """)) {
                    statement.setString(1, label);
                    statement.setQueryTimeout(5);
                    try (var row = statement.executeQuery()) {
                        require(row.next(), "PROBE_CONFIGURATION_NOT_FOUND");
                        require(row.getBoolean("enabled"), "PROBE_CONFIGURATION_DISABLED");
                        require(!row.getBoolean("listening"), "PROBE_MAILBOX_BUSY");
                        var testUntil = row.getTimestamp("connection_test_until");
                        require(testUntil == null || !testUntil.toInstant().isAfter(Instant.now()), "PROBE_MAILBOX_BUSY");
                        String host = row.getString("host");
                        int port = row.getInt("port");
                        String username = row.getString("username");
                        String folder = row.getString("folder");
                        require("imap.163.com".equalsIgnoreCase(host) && port == 993
                                && "INBOX".equals(folder) && "PASSWORD".equals(row.getString("auth_mode"))
                                && username != null && username.toLowerCase(java.util.Locale.ROOT).endsWith("@163.com"),
                                "PROBE_TARGET_OUTSIDE_APPROVAL");
                        UUID accountId = row.getObject("official_account_id", UUID.class);
                        MailSecretCipher cipher = new MailSecretCipher(required("MAIL_ASSISTANCE_ENCRYPTION_KEY"));
                        String credential = cipher.decrypt(row.getString("password_cipher"),
                                new MailboxConfig(accountId).aad("password"));
                        settings = new MailTransport.Settings(host, port, username, folder, MailboxConfig.AuthMode.PASSWORD, credential);
                        require(!row.next(), "PROBE_CONFIGURATION_AMBIGUOUS");
                    }
                } finally { connection.rollback(); }
            }
            ImapsMailTransport transport = new ImapsMailTransport(
                    new MailTargetPolicy(new MailDnsResolver(new ObjectMapper())), new MailBodyReader());
            try {
                attempted = true;
                try (MailTransport.Connection mailbox = transport.open(settings)) {
                    var cursor = mailbox.baseline();
                    require(cursor.uidValidity() > 0 && cursor.nextUid() > 0, "MAIL_CURSOR_UNAVAILABLE");
                    result = "CONNECTED_READ_ONLY";
                }
            } finally { transport.destroy(); }
        } catch (com.pingche.web.auth.DomainException failure) {
            Set<String> allowed = Set.of("MAIL_AUTH_FAILED", "MAIL_TLS_FAILED", "MAIL_CONNECTION_TIMEOUT",
                    "MAIL_DNS_FAILED", "MAIL_HOST_BLOCKED", "MAIL_FOLDER_FAILED", "MAIL_CURSOR_UNAVAILABLE",
                    "MAIL_CONNECTION_FAILED", "MAIL_ENCRYPTION_UNAVAILABLE", "MAIL_CREDENTIALS_UNAVAILABLE");
            result = allowed.contains(failure.getCode()) ? failure.getCode() : "PROBE_FAILED";
        } catch (SQLException failure) {
            result = "PROBE_DATABASE_UNAVAILABLE";
        } catch (ProbeRejected failure) {
            result = failure.safeCode;
        } catch (RuntimeException failure) {
            result = "PROBE_FAILED";
        }
        System.out.println("SAVED_MAILBOX_PROBE_ATTEMPTED=" + attempted);
        System.out.println("SAVED_MAILBOX_PROBE_RESULT=" + result);
        assertTrue("CONNECTED_READ_ONLY".equals(result), result);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        require(value != null && !value.isBlank(), "PROBE_CONFIGURATION_UNAVAILABLE");
        return value;
    }

    private static void require(boolean valid, String safeCode) {
        if (!valid) throw new ProbeRejected(safeCode);
    }

    private static final class ProbeRejected extends RuntimeException {
        private final String safeCode;
        private ProbeRejected(String safeCode) { super(null, null, false, false); this.safeCode = safeCode; }
    }
}
