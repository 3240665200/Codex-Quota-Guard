package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProxyNodeService {
    private final ProxyNodeRepository nodes;
    private final ProxyNodeParser parser;
    private final ProxySecretCipher cipher;
    private final ProxySubscriptionFetcher subscriptions;
    private final ManagedProxyGateway gateway;
    private final Clock clock;

    public ProxyNodeService(ProxyNodeRepository nodes, ProxyNodeParser parser, ProxySecretCipher cipher,
            ProxySubscriptionFetcher subscriptions, ManagedProxyGateway gateway, Clock clock) {
        this.nodes = nodes; this.parser = parser; this.cipher = cipher; this.subscriptions = subscriptions;
        this.gateway = gateway; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<ProxyNodeResponse> list() {
        return nodes.findAllByOrderByLabelAscIdAsc().stream().map(ProxyNodeResponse::from).toList();
    }

    @Transactional
    public ProxyNodeResponse create(ProxyNodeRequest request) {
        ProxyNodeDefinition definition = parser.parse(request.uri());
        ProxyNode node = createNode(ProxyNodeParser.label(request.label()), definition, request.enabled(), clock.instant());
        return ProxyNodeResponse.from(nodes.saveAndFlush(node));
    }

    @Transactional
    public ProxyNodeResponse update(UUID id, ProxyNodeRequest request) {
        ProxyNode node = required(id);
        if (request.version() == null || request.version() != node.getVersion())
            throw new DomainException("PROXY_NODE_VERSION_CONFLICT", "代理节点已变更，请刷新后重试。", HttpStatus.CONFLICT);
        ProxyNodeDefinition definition = null;
        String encrypted = null;
        if (request.uri() != null && !request.uri().isBlank()) {
            definition = parser.parse(request.uri());
            encrypted = cipher.encrypt(definition.outboundJson(), id.toString());
        }
        node.update(ProxyNodeParser.label(request.label()), definition, encrypted, request.enabled(), clock.instant());
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

    @Transactional
    public ImportResult importNodes(ProxyImportRequest request) {
        boolean content = request.content() != null && !request.content().isBlank();
        boolean url = request.subscriptionUrl() != null && !request.subscriptionUrl().isBlank();
        if (content == url) throw new DomainException("PROXY_IMPORT_SOURCE_INVALID", "请提供订阅内容或 HTTPS 订阅地址其中一项。", HttpStatus.BAD_REQUEST);
        String source = content ? request.content() : subscriptions.fetch(request.subscriptionUrl());
        List<ProxyNodeDefinition> parsed = parser.parseSubscription(source);
        Instant now = clock.instant();
        // Parse and encrypt every node before inserting any row. The transaction is all-or-nothing.
        List<ProxyNode> created = parsed.stream().map(definition -> createNode(definition.label(), definition, true, now)).toList();
        List<ProxyNodeResponse> saved = nodes.saveAllAndFlush(created).stream().map(ProxyNodeResponse::from).toList();
        return new ImportResult(saved, saved.size());
    }

    public TestResult test(UUID id) {
        required(id);
        CompletableFuture<HttpResponse<Void>> pending = null;
        try (ProxyLease lease = gateway.open(id)) {
            HttpClient client = HttpClient.newBuilder().proxy(lease.proxySelector()).connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://chatgpt.com/"))
                    .method("HEAD", HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(15)).build();
            pending = client.sendAsync(request, HttpResponse.BodyHandlers.discarding());
            int status = pending.get(15, TimeUnit.SECONDS).statusCode();
            String outcome = status == 403 ? "ACCESS_BLOCKED" : status == 401 ? "AUTH_REQUIRED"
                    : status >= 200 && status < 300 ? "CONNECTED" : "HTTP_RESPONSE";
            return new TestResult(true, status, outcome);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); throw connectionFailed();
        } catch (ExecutionException | TimeoutException ignored) { throw connectionFailed(); }
        catch (RuntimeException error) {
            if (error instanceof DomainException domain) throw domain;
            throw connectionFailed();
        } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    private ProxyNode createNode(String label, ProxyNodeDefinition definition, boolean enabled, Instant now) {
        UUID id = UUID.randomUUID();
        return new ProxyNode(id, label, definition, cipher.encrypt(definition.outboundJson(), id.toString()), enabled, now);
    }
    private ProxyNode required(UUID id) {
        return nodes.findById(id).orElseThrow(() -> new DomainException("PROXY_NODE_NOT_FOUND", "代理节点不存在。", HttpStatus.NOT_FOUND));
    }
    private DomainException connectionFailed() {
        return new DomainException("PROXY_CONNECTION_FAILED", "代理连接测试失败，未回退直连。", HttpStatus.BAD_GATEWAY);
    }
    public record ImportResult(List<ProxyNodeResponse> nodes, int importedCount) { }
    public record TestResult(boolean connected, Integer httpStatus, String status) { }
}
