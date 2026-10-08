package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonalQuotaOverviewTest {

    @Test
    void rejectsNegativePersonalTokenMeasurements() {
        assertThatIllegalArgumentException().isThrownBy(() -> new QuotaUsage(
                QuotaMeasurementStatus.CLIENT_REPORTED,
                -1L,
                0L,
                null,
                null,
                null,
                null,
                "2026-01-01T00:00:00Z"
        ));
    }

    @Test
    void rejectsAnUnconfiguredTokenLimitThatClaimsQuotaIsAllowed() {
        var clientReportedUsage = new QuotaUsage(
                QuotaMeasurementStatus.CLIENT_REPORTED,
                20L,
                10L,
                null,
                null,
                null,
                null,
                "2026-01-01T00:00:00Z"
        );

        assertThatIllegalArgumentException().isThrownBy(() -> new PersonalQuotaOverview(
                user(),
                null,
                new QuotaPolicy(null, null, "UNCONFIGURED", "UNCONFIGURED"),
                clientReportedUsage,
                true,
                null
        ));
    }

    @Test
    void acceptsAnUnlimitedFiveHourWindowWithAWeeklyLimit() {
        var usage = new QuotaUsage(QuotaMeasurementStatus.CLIENT_REPORTED,
                20L, 10L, null, 90L, null, null, "2026-01-01T00:00:00Z");

        assertThatCode(() -> new PersonalQuotaOverview(user(), null,
                new QuotaPolicy(null, 100L, "UNCONFIGURED", "USER"),
                usage, true, null)).doesNotThrowAnyException();
    }

    private static SystemUser user() {
        return new SystemUser(
                UUID.randomUUID(),
                "alice@example.com",
                "Alice",
                "USER",
                "ACTIVE",
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
    }
}
