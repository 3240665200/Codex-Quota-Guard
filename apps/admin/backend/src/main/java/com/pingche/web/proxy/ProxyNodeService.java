package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ProxyNodeService {
    private final ProxyNodeRepository nodes;
    private final ProxyNodeParser parser;
    private final ProxySecretCipher cipher;
    private final ProxyNodeProbe probe;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Set<UUID> testsInProgress = ConcurrentHashMap.newKeySet();

    public ProxyNodeService(ProxyNodeRepository nodes, ProxyNodeParser parser, ProxySecretCipher cipher,
            ProxyNodeProbe probe, Clock clock, PlatformTransactionManager transactionManager) {
        this.nodes = nodes; this.parser = parser; this.cipher = cipher; this.probe = probe;
        this.clock = clock; this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<ProxyNodeResponse> list() {
        return nodes.findAllByOrderByLabelAscIdAsc().stream().map(ProxyNodeResponse::from).toList();
    }

    @Transactional
    public ProxyNodeResponse create(ProxyNodeRequest request) {
        ProxyNodeDefinition definition = parser.parse(request, null);
        ProxyNode node = createNode(ProxyNodeParser.label(request.label()), definition, request.enabled(), clock.instant());
        return ProxyNodeResponse.from(nodes.saveAndFlush(node));
    }

    @Transactional
    public ProxyNodeResponse update(UUID id, ProxyNodeRequest request) {
        ProxyNode node = required(id);
        if (node.getSourceSubscriptionId() != null)
            throw new DomainException("PROXY_NODE_SUBSCRIPTION_MANAGED", "订阅节点配置由手动更新订阅维护，请使用启用或停用操作。", HttpStatus.CONFLICT);
        if (request.version() == null || request.version() != node.getVersion())
            throw new DomainException("PROXY_NODE_VERSION_CONFLICT", "代理节点已变更，请刷新后重试。", HttpStatus.CONFLICT);
        String previous = cipher.decrypt(node.encryptedConfiguration(), id.toString());
        ProxyNodeDefinition definition = parser.parse(request, previous);
        String encrypted = cipher.encrypt(definition.outboundJson(), id.toString());
        if (definition.outboundJson().equals(previous) && definition.protocol().equals(node.getProtocol())) definition = null;
        node.update(ProxyNodeParser.label(request.label()), definition, encrypted, request.enabled(), clock.instant());
        return ProxyNodeResponse.from(nodes.saveAndFlush(node));
    }

    @Transactional
    public ProxyNodeResponse setEnabled(UUID id, ProxyNodeEnabledRequest request) {
        ProxyNode node = required(id);
        if (request.version() == null || request.version() != node.getVersion())
            throw new DomainException("PROXY_NODE_VERSION_CONFLICT", "代理节点已变更，请刷新后重试。", HttpStatus.CONFLICT);
        if (Boolean.TRUE.equals(request.enabled()) && node.isSourceMissing())
            throw new DomainException("PROXY_NODE_SOURCE_MISSING", "该节点已从订阅消失，请先手动更新订阅确认其恢复。", HttpStatus.CONFLICT);
        node.setEnabled(Boolean.TRUE.equals(request.enabled()), clock.instant());
        return ProxyNodeResponse.from(nodes.saveAndFlush(node));
    }

    @Transactional
    public void delete(UUID id) {
        ProxyNode node = required(id);
        try { nodes.delete(node); nodes.flush(); }
        catch (DataIntegrityViolationException ignored) {
            throw new DomainException("PROXY_NODE_IN_USE", "代理节点仍被官方账户绑定，请先更换账户绑定。", HttpStatus.CONFLICT);
        }
    }

    public TestResult test(UUID id) {
        if (!testsInProgress.add(id)) throw new DomainException("PROXY_TEST_BUSY", "该节点正在测试，请稍后重试。", HttpStatus.CONFLICT);
        try {
            ProxyNode original = required(id);
            if (original.getTestedAt() != null && original.getTestedAt().plusSeconds(10).isAfter(clock.instant()))
                throw new DomainException("PROXY_TEST_RATE_LIMITED", "请等待 10 秒后再测试该节点。", HttpStatus.TOO_MANY_REQUESTS);
            ProxyNodeProbe.Result result = null;
            DomainException failure = null;
            try { result = probe.test(id); }
            catch (DomainException error) { failure = error; }
            ProxyNodeProbe.Result completed = result;
            Instant testedAt = clock.instant();
            // Do not attach an old result to a node whose credentials/address changed while the request was in flight.
            transaction.executeWithoutResult(ignored -> {
                ProxyNode current = required(id);
                if (current.getVersion() != original.getVersion()) throw new DomainException("PROXY_NODE_VERSION_CONFLICT", "节点已变更，请重新测试。", HttpStatus.CONFLICT);
                current.recordTest(completed, testedAt);
                nodes.saveAndFlush(current);
            });
            if (failure != null) throw failure;
            return new TestResult(true, 200, "CONNECTED", result.egressIp(), result.country(), result.region(), result.city(), testedAt);
        } finally { testsInProgress.remove(id); }
    }

    private ProxyNode createNode(String label, ProxyNodeDefinition definition, boolean enabled, Instant now) {
        UUID id = UUID.randomUUID();
        return new ProxyNode(id, label, definition, cipher.encrypt(definition.outboundJson(), id.toString()), enabled, now);
    }
    private ProxyNode required(UUID id) {
        return nodes.findById(id).orElseThrow(() -> new DomainException("PROXY_NODE_NOT_FOUND", "代理节点不存在。", HttpStatus.NOT_FOUND));
    }
    public record TestResult(boolean connected, Integer httpStatus, String status, String egressIp,
            String country, String region, String city, Instant testedAt) { }
}
