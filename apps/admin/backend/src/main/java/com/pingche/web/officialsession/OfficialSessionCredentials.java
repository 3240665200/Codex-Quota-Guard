package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import java.util.HashSet;
import java.util.Set;
import org.springframework.http.HttpStatus;

record OfficialSessionCredentials(String authorization, String cookie, String requestedAccountId) {
    void validate() {
        if (authorization == null || authorization.length() > 16_384
                || !authorization.matches("Bearer [A-Za-z0-9._~+/-]+=*")
                || requestedAccountId == null || !requestedAccountId.matches("[A-Za-z0-9_-]{1,160}")) throw invalid();
        if (cookie == null || cookie.isBlank()) return;
        if (cookie.length() > 32_768 || cookie.chars().anyMatch(c -> c < 32 || c == 127)) throw invalid();
        Set<String> names = new HashSet<>();
        for (String part : cookie.split(";", -1)) {
            String entry = part.trim();
            if (!entry.matches("__Secure-next-auth\\.session-token(?:\\.[0-9]{1,3})?=[^;\\s\\p{Cntrl}]+")) throw invalid();
            if (!names.add(entry.substring(0, entry.indexOf('=')))) throw invalid();
        }
    }

    static DomainException invalid() {
        return new DomainException("OFFICIAL_CREDENTIALS_INVALID", "Official authorization fields are invalid", HttpStatus.BAD_REQUEST);
    }

    @Override public String toString() { return "OfficialSessionCredentials[redacted]"; }
}
