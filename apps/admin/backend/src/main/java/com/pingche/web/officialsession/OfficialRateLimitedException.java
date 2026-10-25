package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.http.HttpStatus;

final class OfficialRateLimitedException extends DomainException {
    private final Instant retryAt;
    OfficialRateLimitedException(String value, Instant now) {
        super("OFFICIAL_RATE_LIMITED", "官方请求受限，已延后检测。", HttpStatus.TOO_MANY_REQUESTS);
        Instant parsed = now.plusSeconds(900);
        if (value != null && value.length() <= 128) {
            try {
                if (value.trim().matches("[0-9]{1,9}")) parsed = now.plusSeconds(Long.parseLong(value.trim()));
                else parsed = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            } catch (RuntimeException ignored) { /* Fall back to a fifteen-minute cooldown. */ }
        }
        retryAt = parsed.isAfter(now) ? parsed : now.plusSeconds(60);
    }
    Instant retryAt() { return retryAt; }
}
