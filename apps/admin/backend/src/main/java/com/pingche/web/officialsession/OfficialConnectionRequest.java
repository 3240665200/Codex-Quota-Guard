package com.pingche.web.officialsession;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record OfficialConnectionRequest(@NotBlank @Size(max = 16_384) String authorization,
        @Size(max = 32_768) String cookie, @NotBlank @Size(max = 160) String requestedAccountId,
        UUID proxyNodeId, @NotNull @PositiveOrZero Long version, UUID frontProxyNodeId) {
    public OfficialConnectionRequest(String authorization, String cookie, String requestedAccountId, UUID proxyNodeId, Long version) {
        this(authorization, cookie, requestedAccountId, proxyNodeId, version, null);
    }
    @Override public String toString() { return "OfficialConnectionRequest[redacted]"; }
}
