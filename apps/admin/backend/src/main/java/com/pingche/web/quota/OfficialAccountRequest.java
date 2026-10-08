package com.pingche.web.quota;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OfficialAccountRequest(
        @NotBlank @Size(max = 120) String label,
        @NotNull @Min(0) @Max(1_000_000_000_000L) Long estimatedTotalTokens,
        @Email @Size(max = 320) String reporterEmail,
        Long version
) { }
