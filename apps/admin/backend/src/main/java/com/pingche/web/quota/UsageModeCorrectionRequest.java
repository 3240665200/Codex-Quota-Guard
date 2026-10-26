package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record UsageModeCorrectionRequest(@NotNull @Size(min=1,max=100) List<@NotNull @Valid Item> events) {
    public record Item(@NotNull @Pattern(regexp="codex-local-(usage|cumulative):v1:[0-9a-f]{64}") String eventId,
            @NotNull @Pattern(regexp="default|standard|fast|priority") String serviceTier) { }
}
