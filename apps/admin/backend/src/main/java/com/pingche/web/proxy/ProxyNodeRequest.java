package com.pingche.web.proxy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProxyNodeRequest(@NotBlank @Size(max = 120) String label, @Size(max = 16_384) String uri,
        @NotNull Boolean enabled, Long version) {
    @Override public String toString() { return "ProxyNodeRequest[redacted]"; }
}
