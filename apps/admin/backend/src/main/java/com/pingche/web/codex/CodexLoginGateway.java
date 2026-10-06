package com.pingche.web.codex;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** Server-only bridge. Neither this handle nor its credentials are API response types. */
public interface CodexLoginGateway {

    default Login open() { return open(CodexLoginMode.DEVICE_CODE); }

    Login open(CodexLoginMode mode);

    interface Login extends AutoCloseable {
        String verificationUrl();
        String userCode();
        default String authUrl() { return null; }
        Optional<AuthorizedAccount> poll();
        @Override
        void close();
    }

    final class AuthorizedAccount {
        private final String email;
        private final JsonNode credential;

        public AuthorizedAccount(String email, JsonNode credential) {
            this.email = email;
            this.credential = credential;
        }

        public String email() { return email; }
        public JsonNode credential() { return credential; }

        @Override
        public String toString() { return "AuthorizedAccount[redacted]"; }
    }
}
