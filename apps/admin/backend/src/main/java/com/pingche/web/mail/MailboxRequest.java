package com.pingche.web.mail;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MailboxRequest(boolean enabled, @NotBlank @Size(max = 32) String provider,
        @NotBlank @Size(max = 253) String host, @Min(1) @Max(65535) int port,
        @NotBlank @Size(max = 320) String username, @NotBlank @Email @Size(max = 320) String recipientEmail,
        @NotBlank @Size(max = 200) String folder, @NotBlank @Size(max = 320) String senderEmail,
        @NotNull MailboxConfig.AuthMode authMode, @Size(max = 320) String oauthClientId,
        @Size(max = 100) String oauthTenant, @Size(max = 8192) String password,
        @Size(max = 8192) String oauthClientSecret, @Size(max = 8192) String oauthRefreshToken) {
    @Override public String toString() { return "MailboxRequest[redacted]"; }
}
