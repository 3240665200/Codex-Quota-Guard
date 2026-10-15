package com.pingche.web.proxy;

import java.util.UUID;

public interface ManagedProxyGateway {
    enum Purpose { OFFICIAL_SESSION, BROWSER_LOGIN }
    ProxyLease open(UUID nodeId);
    default ProxyLease open(UUID nodeId, Purpose purpose) {
        if (purpose != Purpose.OFFICIAL_SESSION) throw new IllegalArgumentException("Unsupported proxy purpose");
        return open(nodeId);
    }
}
