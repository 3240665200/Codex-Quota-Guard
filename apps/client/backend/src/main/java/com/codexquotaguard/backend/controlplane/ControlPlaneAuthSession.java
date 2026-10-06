package com.codexquotaguard.backend.controlplane;

/**
 * Private-in-process result from the central product authentication API.
 *
 * <p>Never return this type from a Spring controller or a Tauri command. Its
 * token accessors exist solely so {@link com.codexquotaguard.backend.system.SystemSessionService}
 * can hold the values in memory while the desktop app is running.</p>
 */
public final class ControlPlaneAuthSession {

    private final String accessToken;
    private final String refreshToken;
    private final SystemUser user;

    public ControlPlaneAuthSession(String accessToken, String refreshToken, SystemUser user) {
        if (isBlank(accessToken) || isBlank(refreshToken) || user == null) {
            throw new IllegalArgumentException("A complete authentication session is required.");
        }
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.user = user;
    }

    public String accessToken() {
        return accessToken;
    }

    public String refreshToken() {
        return refreshToken;
    }

    public SystemUser user() {
        return user;
    }

    @Override
    public String toString() {
        return "ControlPlaneAuthSession[redacted]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
