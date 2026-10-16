package com.pingche.web.officialsession;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record OfficialAuthorizationPresetRequest(@NotNull @PositiveOrZero Long version,
        UUID proxyNodeId, @NotNull @Pattern(regexp = "[a-f0-9]{64}") String presetRevision) {}
