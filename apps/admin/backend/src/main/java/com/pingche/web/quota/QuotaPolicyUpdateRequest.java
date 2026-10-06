package com.pingche.web.quota;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;

/**
 * A complete replacement of a user's quota policy. Null values deliberately
 * mean "inherit": null teamId removes the team, while null percentage values
 * use the selected team's setting or the 100 percent system default.
 */
public record QuotaPolicyUpdateRequest(
        UUID teamId,
        @Min(0) @Max(100) Integer fiveHourQuotaPercent,
        @Min(0) @Max(100) Integer weeklyQuotaPercent
) {
}
