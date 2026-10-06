package com.pingche.web.team;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamCreateRequest(
        @NotBlank @Size(max = 120) String name,
        @Size(max = 1000) String description,
        Team.Status status,
        @Min(0) @Max(100) Integer fiveHourQuotaPercent,
        @Min(0) @Max(100) Integer weeklyQuotaPercent
) {
}
