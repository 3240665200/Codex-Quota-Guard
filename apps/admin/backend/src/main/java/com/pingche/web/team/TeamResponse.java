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
        long memberCount,
        Instant createdAt,
        Instant updatedAt,
        long version
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
                memberCount,
                team.getCreatedAt(),
                team.getUpdatedAt(),
                team.getVersion()
        );
    }
}
