package com.pingche.web.quota;

import java.time.Instant;
import java.util.UUID;

public record QuotaOverviewResponse(
        QuotaSystemUserResponse user,
        TeamReference team,
        TeamQuotaOverviewResponse teamQuota,
        Policy policy,
        Usage usage,
        boolean allowed,
        String blockReason
) {

    public record TeamReference(UUID id, String name) {
    }

    public record Policy(
            Long fiveHourTokenLimit,
            Long weeklyTokenLimit,
            Source fiveHourSource,
            Source weeklySource
    ) {
        public enum Source {
            USER,
            TEAM,
            UNCONFIGURED
        }
    }

    public record Usage(
            MeasurementStatus measurementStatus,
            long fiveHourUsedTokens,
            long weeklyUsedTokens,
            Long fiveHourRemainingTokens,
            Long weeklyRemainingTokens,
            Instant fiveHourResetsAt,
            Instant weeklyResetsAt,
            Instant measuredAt
    ) { }

    public enum MeasurementStatus {
        CLIENT_REPORTED
    }
}
