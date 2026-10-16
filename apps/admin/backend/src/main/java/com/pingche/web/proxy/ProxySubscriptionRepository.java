package com.pingche.web.proxy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProxySubscriptionRepository extends JpaRepository<ProxySubscription, UUID> {
    List<ProxySubscription> findAllByOrderByLabelAscIdAsc();
}
