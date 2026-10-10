package com.codexquotaguard.backend.controlplane;

import com.codexquotaguard.backend.usage.CodexUsageEvent;
import java.util.List;
import java.util.UUID;

/**
 * Fixed, minimal boundary to the central Java backend.
 *
 * <p>The desktop UI cannot select a URL or pass a bearer token through this
 * boundary. The implementation uses only the product API endpoints declared
 * here.</p>
 */
public interface ControlPlaneGateway {

    ControlPlaneAuthSession login(String email, String password);

    ControlPlaneAuthSession refresh(String refreshToken);

    SystemUser current(String accessToken);

    PersonalQuotaOverview quotaOverview(String accessToken);

    default UserUsageEventPage usageEvents(String accessToken, int page, int size) {
        throw ControlPlaneException.unavailable();
    }

    /** One-time device-scoped reporting credential, consumed only by the local pairing writer. */
    default MeterEnrollment enrollMeter(String accessToken, UUID deviceId) {
        throw ControlPlaneException.unavailable();
    }

    /** Reports idempotent local token events for the current product user. */
    default PersonalQuotaOverview reportUsage(String accessToken, List<CodexUsageEvent> events) {
        throw ControlPlaneException.unavailable();
    }

    /** Uploads non-secret official quota metadata for the admin dashboard. */
    default void reportOfficialQuota(String accessToken, OfficialCodexRateLimits snapshot) {
        throw ControlPlaneException.unavailable();
    }

    void logout(String accessToken, String refreshToken);
}
