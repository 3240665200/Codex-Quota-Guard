package com.pingche.web.proxy;

import java.util.UUID;

public interface ManagedProxyGateway {
    ProxyLease open(UUID nodeId);
}
