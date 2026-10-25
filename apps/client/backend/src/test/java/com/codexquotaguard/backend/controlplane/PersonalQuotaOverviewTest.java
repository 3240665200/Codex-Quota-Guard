package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
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

    @Test
    void preservesUnsupportedAndEmptyTeamMemberProjectionsAsDifferentStates() {
        var policy = new QuotaPolicy(null, 100L, "UNCONFIGURED", "USER");
        var usage = new QuotaUsage(QuotaMeasurementStatus.CLIENT_REPORTED,
                0L, 0L, null, 100L, null, null, null);

        assertThat(new PersonalQuotaOverview(user(), null, policy, usage, true, null).teamMembers())
                .isNull();
        assertThat(new PersonalQuotaOverview(user(), null, null, policy, usage, true, null).teamMembers())
                .isNull();
        assertThat(new PersonalQuotaOverview(user(), null, null, policy, usage, true, null, List.of())
                .teamMembers()).isEmpty();
    }

    @Test
    void takesAnImmutableSnapshotOfTeamMemberUsage() {
        var member = new TeamMemberUsage(UUID.randomUUID(), "Alice", "ACTIVE", 10L, 20L, 30L,
                null, null, null, null, null);
        var members = new ArrayList<>(List.of(member));
        var overview = new PersonalQuotaOverview(user(), new QuotaTeam(UUID.randomUUID().toString(), "Design"),
                null, new QuotaPolicy(null, 100L, "UNCONFIGURED", "USER"),
                new QuotaUsage(QuotaMeasurementStatus.CLIENT_REPORTED,
                        10L, 20L, null, 80L, null, null, null), true, null, members);

        members.clear();

        assertThat(overview.teamMembers()).containsExactly(member);
        assertThatThrownBy(() -> overview.teamMembers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
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
