package com.pingche.web.proxy;

import java.time.Instant;
import java.util.UUID;

public record ProxyNodeResponse(UUID id, String label, String protocol, String host, int port, boolean enabled,
        boolean hasCredentials, Instant createdAt, Instant updatedAt, long version,
        String egressIp, String country, String region, String city, Instant testedAt, String testStatus) {
    static ProxyNodeResponse from(ProxyNode node) {
        return new ProxyNodeResponse(node.getId(), node.getLabel(), node.getProtocol(), node.getHost(), node.getPort(),
                node.isEnabled(), node.hasCredentials(), node.getCreatedAt(), node.getUpdatedAt(), node.getVersion(),
                node.getEgressIp(), node.getCountry(), node.getRegion(), node.getCity(), node.getTestedAt(), node.getTestStatus());
    }
}
