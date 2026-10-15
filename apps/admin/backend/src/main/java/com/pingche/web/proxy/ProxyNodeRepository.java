package com.pingche.web.proxy;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProxyNodeRepository extends JpaRepository<ProxyNode, UUID> {
    List<ProxyNode> findAllByOrderByLabelAscIdAsc();
}
