package com.pingche.web.mail;

public record MailboxResponse(boolean configured, boolean enabled, String provider, String host, Integer port,
        String username, String recipientEmail, String folder, String senderEmail, MailboxConfig.AuthMode authMode,
        String oauthClientId, String oauthTenant, boolean hasPassword, boolean hasClientSecret,
        boolean hasRefreshToken, boolean encryptionReady) {
}
