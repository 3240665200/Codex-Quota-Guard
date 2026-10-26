package com.pingche.web.quota;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OfficialAccountRequest(
        @NotBlank @Size(max = 120) String label,
        @Min(0) @Max(1_000_000_000_000L) Long estimatedTotalTokens,
        @Email @Size(max = 320) String reporterEmail,
        Long version,
        @jakarta.validation.constraints.DecimalMin("0") @jakarta.validation.constraints.DecimalMax("1000000000000")
        @jakarta.validation.constraints.Digits(integer = 13, fraction = 12) java.math.BigDecimal budgetUsd
) {
    public OfficialAccountRequest(String label, Long tokens, String reporter, Long version) { this(label, tokens, reporter, version, null); }
}
