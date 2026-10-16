package com.pingche.web.officialsession;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;

public record OfficialConnectionProxyRequest(@NotNull UUID proxyNodeId, UUID frontProxyNodeId,
        @NotNull @PositiveOrZero Long version) { }
