package com.pingche.web.team;

import java.time.Instant;
import java.util.UUID;

public record TeamResponse(
        UUID id,
        String name,
        String description,
        Team.Status status,
        int fiveHourQuotaPercent,
        int weeklyQuotaPercent,
        Long fiveHourTokenLimit,
        Long weeklyTokenLimit,
        Long totalTokenLimit,
        UUID officialAccountId,
        long memberCount,
        Instant createdAt,
        Instant updatedAt,
        long version,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.math.BigDecimal fiveHourUsdLimit,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.math.BigDecimal weeklyUsdLimit,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.math.BigDecimal totalUsdLimit
) {

    static TeamResponse from(Team team, long memberCount) {
        return new TeamResponse(
                team.getId(),
                team.getName(),
                team.getDescription(),
                team.getStatus(),
                team.getFiveHourQuotaPercent(),
                team.getWeeklyQuotaPercent(),
                team.getFiveHourTokenLimit(),
                team.getWeeklyTokenLimit(),
                team.getTotalTokenLimit(),
                team.getOfficialAccountId(),
                memberCount,
                team.getCreatedAt(),
                team.getUpdatedAt(),
                team.getVersion(), team.getFiveHourUsdLimit(), team.getWeeklyUsdLimit(), team.getTotalUsdLimit()
        );
    }
}
