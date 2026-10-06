package com.pingche.web.quota;

import com.pingche.web.codex.ClientSystemUserResponse;
import com.pingche.web.codex.ManagedCodexAccountResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record QuotaOverviewResponse(
        boolean assigned,
        ClientSystemUserResponse user,
        ManagedCodexAccountResponse codexAccount,
        TeamReference team,
        Policy policy,
        Usage usage,
        boolean allowed,
        String blockReason
) {

    public record TeamReference(UUID id, String name) {
    }

    public record Policy(
            int fiveHourLimitPercent,
            int weeklyLimitPercent,
            Source fiveHourSource,
            Source weeklySource
    ) {
        public enum Source {
            USER,
            TEAM,
            DEFAULT
        }
    }

    public record Usage(
            MeasurementStatus measurementStatus,
            BigDecimal fiveHourUsedPercent,
            BigDecimal weeklyUsedPercent,
            BigDecimal fiveHourRemainingPercent,
            BigDecimal weeklyRemainingPercent,
            Instant fiveHourResetsAt,
            Instant weeklyResetsAt,
            Instant measuredAt
    ) {
        static Usage notMeasured() {
            return new Usage(MeasurementStatus.NOT_MEASURED, null, null, null, null, null, null, null);
        }
    }

    public enum MeasurementStatus {
        CURRENT,
        NOT_MEASURED
    }
}
