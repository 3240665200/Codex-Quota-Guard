package com.codexquotaguard.backend.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/**
 * Verifies the ephemeral bearer token supplied by the Electron main process.
 * The token is deliberately never persisted or exposed to a renderer.
 */
public final class ApiTokenVerifier {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{32,256}$");

    private final byte[] expectedToken;

    public ApiTokenVerifier(String token) {
        if (!isValidToken(token)) {
            throw new IllegalArgumentException("API token must be an unpadded base64url value between 32 and 256 characters");
        }
        this.expectedToken = token.getBytes(StandardCharsets.US_ASCII);
    }

    public boolean isAuthorized(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }

        String presentedToken = authorizationHeader.substring("Bearer ".length());
        if (!isValidToken(presentedToken)) {
            return false;
        }
        return MessageDigest.isEqual(expectedToken, presentedToken.getBytes(StandardCharsets.US_ASCII));
    }

    public static boolean isValidToken(String token) {
        return token != null && TOKEN_PATTERN.matcher(token).matches();
    }
}
