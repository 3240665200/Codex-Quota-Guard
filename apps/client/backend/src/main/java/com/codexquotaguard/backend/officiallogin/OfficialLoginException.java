package com.codexquotaguard.backend.officiallogin;

/** Fixed classifications only: never retains a credential, URL, body or original exception. */
final class OfficialLoginException extends RuntimeException {
    private final String code;
    OfficialLoginException(String code) { super("Official browser login unavailable", null, false, false); this.code = safeCode(code); }
    String code() { return code; }
    static String safeCode(String value) {
        if (value != null && java.util.Set.of(
                "OFFICIAL_LOGIN_ADMIN_REQUIRED", "OFFICIAL_LOGIN_SESSION_REQUIRED", "OFFICIAL_LOGIN_BUSY", "OFFICIAL_LOGIN_NOT_ACTIVE", "OFFICIAL_LOGIN_SAVING",
                "OFFICIAL_LOGIN_IDENTITY_CHANGED", "OFFICIAL_LOGIN_TIMEOUT", "OFFICIAL_LOGIN_BROWSER_NOT_FOUND",
                "OFFICIAL_LOGIN_PROFILE_UNAVAILABLE", "OFFICIAL_LOGIN_BROWSER_UNAVAILABLE", "OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE",
                "OFFICIAL_LOGIN_REJECTED", "OFFICIAL_LOGIN_CANCEL_UNCONFIRMED", "OFFICIAL_LOGIN_INVALID_PARAMETERS",
                "BROWSER_LOGIN_BUSY", "BROWSER_LOGIN_NOT_FOUND", "BROWSER_LOGIN_NOT_ACTIVE", "BROWSER_LOGIN_SAVING", "BROWSER_LOGIN_EXPIRED",
                "OFFICIAL_AUTH_REQUIRED", "OFFICIAL_CHALLENGE_REQUIRED", "OFFICIAL_ACCESS_BLOCKED", "OFFICIAL_RATE_LIMITED",
                "OFFICIAL_UNAVAILABLE", "OFFICIAL_RESPONSE_INVALID", "OFFICIAL_ACCOUNT_MISMATCH", "OFFICIAL_PROXY_UNAVAILABLE",
                "OFFICIAL_CONNECTION_VERSION_CONFLICT", "OFFICIAL_ENCRYPTION_UNAVAILABLE", "OFFICIAL_ACCOUNT_NOT_FOUND",
                "OFFICIAL_IDENTITY_CHANGE_REQUIRES_DISCONNECT", "OFFICIAL_CONNECTION_CHANGED", "OFFICIAL_REFRESH_BUSY"
        ).contains(value)) return value;
        return "OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE";
    }
}
