package com.pingche.web.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.UUID;
import java.time.Instant;

@Entity
@Table(name = "official_account_mailboxes")
public class MailboxConfig {
    @Id @Column(name = "official_account_id") UUID officialAccountId;
    @Column(nullable = false) boolean enabled;
    @Column(nullable = false, length = 32) String provider;
    @Column(nullable = false, length = 253) String host;
    @Column(nullable = false) int port;
    @Column(nullable = false, length = 320) String username;
    @Column(name = "recipient_email", nullable = false, unique = true, length = 320) String recipientEmail;
    @Column(nullable = false, length = 200) String folder;
    @Column(name = "sender_email", nullable = false, length = 320) String senderEmail;
    @Enumerated(EnumType.STRING) @Column(name = "auth_mode", nullable = false, length = 32) AuthMode authMode;
    @Column(name = "oauth_client_id", length = 320) String oauthClientId;
    @Column(name = "oauth_tenant", length = 100) String oauthTenant;
    @Column(name = "password_cipher", columnDefinition = "text") String passwordCipher;
    @Column(name = "client_secret_cipher", columnDefinition = "text") String clientSecretCipher;
    @Column(name = "refresh_token_cipher", columnDefinition = "text") String refreshTokenCipher;
    @Column(name = "connection_test_until") Instant connectionTestUntil;
    @Column(name = "mailbox_key", nullable = false, unique = true, length = 64) String mailboxKey;
    @Column(nullable = false) UUID revision;
    @Version @Column(nullable = false) long version;

    protected MailboxConfig() { }
    MailboxConfig(UUID officialAccountId) { this.officialAccountId = officialAccountId; }

    String aad(String field) { return "mailbox:" + officialAccountId + ":" + field; }
    boolean hasCredentials() {
        return authMode == AuthMode.PASSWORD ? passwordCipher != null
                : oauthClientId != null && !oauthClientId.isBlank() && refreshTokenCipher != null
                  && (authMode != AuthMode.GOOGLE_OAUTH2 || clientSecretCipher != null);
    }
    public enum AuthMode { PASSWORD, GOOGLE_OAUTH2, MICROSOFT_OAUTH2 }
    @Override public String toString() { return "MailboxConfig[redacted]"; }
}
