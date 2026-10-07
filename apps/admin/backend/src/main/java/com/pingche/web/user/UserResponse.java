package com.pingche.web.user;

import java.time.Instant;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        String status,
        UUID teamId,
        Integer fiveHourQuotaPercent,
        Integer weeklyQuotaPercent,
        Long fiveHourTokenLimit,
        Long weeklyTokenLimit,
        Instant createdAt,
        Instant updatedAt
) {

    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name(),
                user.getStatus().name(),
                user.getTeamId(),
                user.getFiveHourQuotaPercent(),
                user.getWeeklyQuotaPercent(),
                user.getFiveHourTokenLimit(),
                user.getWeeklyTokenLimit(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
