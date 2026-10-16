package com.pingche.web.proxy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ProxySubscriptionResponse(UUID id, String label, boolean hasUrl, String sourceHost,
        long nodeCount, long enabledNodeCount, String status, Instant createdAt, Instant updatedAt,
        Instant refreshedAt, long version, boolean allowInsecureTrojan) {
    static ProxySubscriptionResponse from(ProxySubscription subscription, List<ProxyNode> nodes) {
        return new ProxySubscriptionResponse(subscription.getId(), subscription.getLabel(), true,
                subscription.getSourceHost(), nodes.size(), nodes.stream().filter(ProxyNode::isEnabled).count(),
                subscription.getStatus(), subscription.getCreatedAt(), subscription.getUpdatedAt(),
                subscription.getRefreshedAt(), subscription.getVersion(), subscription.isAllowInsecureTrojan());
    }
}
