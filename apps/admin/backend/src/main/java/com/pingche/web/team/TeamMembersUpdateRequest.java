package com.pingche.web.team;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record TeamMembersUpdateRequest(
        @NotNull @Size(min = 1, max = 100) List<@NotNull @Valid Change> changes
) {
    public record Change(
            @NotNull UUID userId,
            @JsonProperty(value = "expectedTeamId", required = true) UUID expectedTeamId,
            @NotNull Boolean selected
    ) { }
}
