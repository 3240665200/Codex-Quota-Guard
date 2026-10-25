package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class TeamMemberUsageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsActiveAndDisabledMembersWithOptionalMeasurements() throws Exception {
        UUID id = UUID.randomUUID();
        var active = new TeamMemberUsage(id, "Alice", "ACTIVE", 10L, 20L, 30L,
                100L, 200L, 35L, 70L,
                Instant.parse("2026-10-19T01:02:03Z"));
        var disabled = new TeamMemberUsage(id, "Bob", "DISABLED", 0L, 0L, 0L,
                null, null, null, null, null);

        assertThat(mapper.readValue(mapper.writeValueAsString(active), TeamMemberUsage.class)).isEqualTo(active);
        assertThat(mapper.readValue(mapper.writeValueAsString(disabled), TeamMemberUsage.class)).isEqualTo(disabled);
    }

    @Test
    void rejectsMissingMemberIdentityNameAndUnsupportedStatus() {
        UUID id = UUID.randomUUID();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TeamMemberUsage(null, "Alice", "ACTIVE", 0L, 0L, 0L,
                        null, null, null, null, null));
        for (String name : new String[] {null, "", "   "}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new TeamMemberUsage(id, name, "ACTIVE", 0L, 0L, 0L,
                            null, null, null, null, null));
        }
        for (String status : new String[] {null, "", "active", "DELETED"}) {
            assertThatIllegalArgumentException().isThrownBy(() ->
                    new TeamMemberUsage(id, "Alice", status, 0L, 0L, 0L,
                            null, null, null, null, null));
        }
    }

    @Test
    void rejectsNegativeUsageInEveryWindow() {
        UUID id = UUID.randomUUID();
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TeamMemberUsage(id, "Alice", "ACTIVE", -1L, 0L, 0L,
                        null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TeamMemberUsage(id, "Alice", "ACTIVE", 0L, -1L, 0L,
                        null, null, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TeamMemberUsage(id, "Alice", "ACTIVE", 0L, 0L, -1L,
                        null, null, null, null, null));
    }

    @Test
    void preservesZeroPersonalLimitsWithoutDerivingRemainingFromTeamContribution() throws Exception {
        var member = new TeamMemberUsage(UUID.randomUUID(), "Alice", "ACTIVE", 10L, 20L, 30L,
                0L, 0L, 0L, 0L, null);

        assertThat(mapper.readValue(mapper.writeValueAsString(member), TeamMemberUsage.class)).isEqualTo(member);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fiveHour", "weekly"})
    void rejectsInvalidPersonalLimitsAndRemainingTokens(String window) {
        for (Long[] values : new Long[][] {{-1L, 0L}, {100L, -1L}, {100L, 101L},
                {null, 0L}, {100L, null}, {0L, null}}) {
            boolean fiveHour = "fiveHour".equals(window);
            assertThatIllegalArgumentException().isThrownBy(() -> new TeamMemberUsage(UUID.randomUUID(),
                    "Alice", "ACTIVE", 10L, 20L, 30L,
                    fiveHour ? values[0] : null, fiveHour ? null : values[0],
                    fiveHour ? values[1] : null, fiveHour ? null : values[1], null));
        }
    }

    @Test
    void acceptsExplicitNullPersonalAllowanceFields() throws Exception {
        var member = mapper.readValue(unlimitedMemberJson(), TeamMemberUsage.class);

        assertThat(member.fiveHourTokenLimit()).isNull();
        assertThat(member.weeklyTokenLimit()).isNull();
        assertThat(member.fiveHourRemainingTokens()).isNull();
        assertThat(member.weeklyRemainingTokens()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fiveHourTokenLimit", "weeklyTokenLimit", "fiveHourRemainingTokens", "weeklyRemainingTokens"})
    void rejectsMissingPersonalAllowanceFieldsInsteadOfClaimingUnlimitedQuota(String field) {
        String json = unlimitedMemberJson().replace("\"" + field + "\":null,", "");

        assertThatThrownBy(() -> mapper.readValue(json, TeamMemberUsage.class))
                .isInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fiveHourUsedTokens", "weeklyUsedTokens", "totalUsedTokens"})
    void rejectsMissingOrNullUsageInsteadOfInventingZero(String field) {
        String json = unlimitedMemberJson();

        assertThatThrownBy(() -> mapper.readValue(json.replace("\"" + field + "\":123,", ""), TeamMemberUsage.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> mapper.readValue(json.replace("\"" + field + "\":123", "\"" + field + "\":null"),
                TeamMemberUsage.class)).isInstanceOf(RuntimeException.class);
    }

    private static String unlimitedMemberJson() {
        return """
                {"userId":"00000000-0000-0000-0000-000000000001","displayName":"Alice","status":"ACTIVE",
                "fiveHourUsedTokens":123,"weeklyUsedTokens":123,"totalUsedTokens":123,
                "fiveHourTokenLimit":null,"weeklyTokenLimit":null,
                "fiveHourRemainingTokens":null,"weeklyRemainingTokens":null,"measuredAt":null}
                """;
    }
}
