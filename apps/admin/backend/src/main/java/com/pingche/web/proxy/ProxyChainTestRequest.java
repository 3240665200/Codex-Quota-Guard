package com.pingche.web.proxy;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ProxyChainTestRequest(UUID frontProxyNodeId, @NotNull UUID proxyNodeId) { }
