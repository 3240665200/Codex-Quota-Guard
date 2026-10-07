package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Credential-free official quota summary measured by the signed-in desktop. */
public record OfficialQuotaSnapshotRequest(
        @Size(max = 40) String planType,
        @NotNull @Size(max = 16) List<@NotNull @Valid Window> windows,
        @NotNull Instant fetchedAt
) {
    public record Window(
            @NotBlank @Size(max = 80) String limitId,
            @NotBlank @Size(max = 16) String slot,
            @Min(1) @Max(525600) long windowDurationMins,
            @NotNull BigDecimal usedPercent,
            Instant resetsAt
    ) { }
}
