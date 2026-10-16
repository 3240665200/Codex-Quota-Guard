package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ProxyChainTestService {
    private static final int MAX_RECORDS = 256, MAX_ACTIVE = 2;
    private final ProxyNodeRepository nodes;
    private final ProxyNodeProbe probe;
    private final Clock clock;
    private final Map<Key, Instant> recent = new HashMap<>();
    private final java.util.Set<Key> active = new java.util.HashSet<>();

    public ProxyChainTestService(ProxyNodeRepository nodes, ProxyNodeProbe probe, Clock clock) {
        this.nodes = nodes; this.probe = probe; this.clock = clock;
    }

    public TestResult test(ProxyChainTestRequest request) {
        UUID front = request.frontProxyNodeId(), exit = request.proxyNodeId();
        if (exit == null || exit.equals(front)) throw error("PROXY_CHAIN_INVALID", "请选择不同的前置节点和出口节点。", HttpStatus.BAD_REQUEST);
        Key key = new Key(front, exit);
        reserve(key);
        try {
            requireEnabled(exit);
            if (front != null) requireEnabled(front);
            ProxyNodeProbe.Result result = probe.test(front, exit);
            return new TestResult(true, 200, "CONNECTED", result.egressIp(), result.country(), result.region(),
                    result.city(), clock.instant(), front, exit);
        } finally { synchronized (this) { active.remove(key); } }
    }

    private synchronized void reserve(Key key) {
        Instant now = clock.instant();
        recent.entrySet().removeIf(entry -> !entry.getValue().plusSeconds(10).isAfter(now));
        if (active.contains(key)) throw error("PROXY_TEST_BUSY", "该代理链正在测试。", HttpStatus.CONFLICT);
        if (recent.containsKey(key)) throw error("PROXY_TEST_RATE_LIMITED", "请等待 10 秒后再测试该代理链。", HttpStatus.TOO_MANY_REQUESTS);
        if (active.size() >= MAX_ACTIVE || recent.size() >= MAX_RECORDS)
            throw error("PROXY_CAPACITY_EXCEEDED", "代理链测试繁忙，请稍后重试。", HttpStatus.TOO_MANY_REQUESTS);
        active.add(key); recent.put(key, now);
    }

    private void requireEnabled(UUID id) {
        ProxyNode node = nodes.findById(id).orElseThrow(() -> error("PROXY_NODE_UNAVAILABLE", "代理节点不存在，未回退直连。", HttpStatus.SERVICE_UNAVAILABLE));
        if (!node.isEnabled()) throw error("PROXY_NODE_DISABLED", "代理节点已停用，未回退直连。", HttpStatus.SERVICE_UNAVAILABLE);
    }
    private DomainException error(String code, String message, HttpStatus status) { return new DomainException(code, message, status); }
    private record Key(UUID front, UUID exit) { }
    public record TestResult(boolean connected, Integer httpStatus, String status, String egressIp,
            String country, String region, String city, Instant testedAt, UUID frontProxyNodeId, UUID proxyNodeId) { }
}
