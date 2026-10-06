package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonalQuotaOverviewTest {

    @Test
    void rejectsNumericUsageForAnUnmeasuredResponse() {
        assertThatIllegalArgumentException().isThrownBy(() -> new QuotaUsage(
                QuotaMeasurementStatus.NOT_MEASURED,
                0.0,
                null,
                null,
                null,
                null,
                null,
                null
        ));
    }

    @Test
    void rejectsCurrentUsageWhenAnyUsageOrRemainingValueIsMissing() {
        assertThatIllegalArgumentException().isThrownBy(() -> new QuotaUsage(
                QuotaMeasurementStatus.CURRENT,
                20.0,
                10.0,
                80.0,
                null,
                "2026-01-01T05:00:00Z",
                "2026-01-08T00:00:00Z",
                "2026-01-01T00:00:00Z"
        ));
    }

    @Test
    void rejectsAnUnmeasuredOverviewThatClaimsQuotaIsAllowed() {
        var unmeasured = new QuotaUsage(
                QuotaMeasurementStatus.NOT_MEASURED,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        assertThatIllegalArgumentException().isThrownBy(() -> new PersonalQuotaOverview(
                false,
                new SystemUser(
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
                        null
                ),
                null,
                null,
                new QuotaPolicy(80, 90, "USER", "DEFAULT"),
                unmeasured,
                true,
                null
        ));
    }
}
