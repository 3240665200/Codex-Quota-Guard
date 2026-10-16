package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Service
public class ProxySubscriptionService {
    private final ProxySubscriptionRepository subscriptions;
    private final ProxyNodeRepository nodes;
    private final ProxyNodeParser parser;
    private final ProxySubscriptionFetcher fetcher;
    private final ProxySecretCipher cipher;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

    public ProxySubscriptionService(ProxySubscriptionRepository subscriptions, ProxyNodeRepository nodes,
            ProxyNodeParser parser, ProxySubscriptionFetcher fetcher, ProxySecretCipher cipher, Clock clock,
            PlatformTransactionManager transactionManager, ObjectMapper json) {
        this.subscriptions = subscriptions; this.nodes = nodes; this.parser = parser;
        this.fetcher = fetcher; this.cipher = cipher; this.clock = clock;
        this.json = json;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<ProxySubscriptionResponse> list() {
        return subscriptions.findAllByOrderByLabelAscIdAsc().stream().map(this::response).toList();
    }

    public ProxySubscriptionResponse create(ProxySubscriptionRequest request) {
        String label = ProxyNodeParser.label(request.label());
        URI uri = ProxySubscriptionFetcher.subscriptionUri(request.url());
        UUID id = UUID.randomUUID();
        String encrypted = cipher.encrypt(request.url(), cipherContext(id));
        // Download is direct and bounded. Never use an account's selected proxy, redirect, or imported configuration.
        boolean allowInsecureTrojan = Boolean.TRUE.equals(request.allowInsecureTrojan());
        List<ProxyNodeDefinition> definitions = parser.parseSubscription(fetcher.fetch(request.url()), allowInsecureTrojan);
        return transaction.execute(ignored -> {
            Instant now = clock.instant();
            ProxySubscription subscription = subscriptions.saveAndFlush(new ProxySubscription(id, label, encrypted,
                    ProxyNodeParser.normalizeHost(uri.getHost()), allowInsecureTrojan, now));
            synchronize(subscription, definitions, now);
            return response(subscription);
        });
    }

    @Transactional
    public ProxySubscriptionResponse update(UUID id, ProxySubscriptionRequest request) {
        ProxySubscription subscription = required(id);
        checkVersion(subscription, request.version());
        String encrypted = null;
        String host = null;
        if (request.url() != null && !request.url().isBlank()) {
            URI uri = ProxySubscriptionFetcher.subscriptionUri(request.url());
            encrypted = cipher.encrypt(request.url(), cipherContext(id));
            host = ProxyNodeParser.normalizeHost(uri.getHost());
        }
        Instant now = clock.instant();
        if (subscription.isAllowInsecureTrojan() && Boolean.FALSE.equals(request.allowInsecureTrojan()))
            revokeInsecureTrojan(nodes.findAllBySourceSubscriptionId(id), now);
        subscription.edit(ProxyNodeParser.label(request.label()), encrypted, host, request.allowInsecureTrojan(), now);
        return response(subscriptions.saveAndFlush(subscription));
    }

    public ProxySubscriptionResponse refresh(UUID id, long version) {
        if (!refreshing.add(id)) throw new DomainException("PROXY_SUBSCRIPTION_BUSY", "该订阅正在更新，请稍后重试。", HttpStatus.CONFLICT);
        try {
            ProxySubscription snapshot = required(id);
            checkVersion(snapshot, version);
            String url = cipher.decrypt(snapshot.encryptedUrl(), cipherContext(id));
            List<ProxyNodeDefinition> definitions = parser.parseSubscription(fetcher.fetch(url), snapshot.isAllowInsecureTrojan());
            return transaction.execute(ignored -> {
                ProxySubscription current = required(id);
                checkVersion(current, version);
                Instant now = clock.instant();
                synchronize(current, definitions, now);
                current.refreshed(now);
                subscriptions.saveAndFlush(current);
                return response(current);
            });
        } finally { refreshing.remove(id); }
    }

    @Transactional
    public void delete(UUID id, long version) {
        ProxySubscription subscription = required(id);
        checkVersion(subscription, version);
        List<ProxyNode> existing = nodes.findAllBySourceSubscriptionId(id);
        Instant now = clock.instant();
        revokeInsecureTrojan(existing, now);
        existing.forEach(node -> node.detachSubscription(now));
        nodes.saveAllAndFlush(existing);
        subscriptions.delete(subscription);
        subscriptions.flush();
    }

    private void revokeInsecureTrojan(List<ProxyNode> existing, Instant now) {
        for (ProxyNode node : existing) {
            if (!node.getProtocol().equals("TROJAN")) continue;
            String configuration = cipher.decrypt(node.encryptedConfiguration(), node.getId().toString());
            try {
                JsonNode outbound = json.readTree(configuration);
                JsonNode tls = outbound == null ? null : outbound.get("tls");
                if (tls == null || !tls.isObject()) throw new IllegalArgumentException();
                if (!tls.path("insecure").asBoolean()) continue;
                ((ObjectNode) tls).put("insecure", false);
                String verifiedConfiguration = json.writeValueAsString(outbound);
                ProxyNodeDefinition definition = new ProxyNodeDefinition(node.getLabel(), node.getProtocol(),
                        node.getHost(), node.getPort(), verifiedConfiguration, node.hasCredentials());
                node.update(node.getLabel(), definition,
                        cipher.encrypt(verifiedConfiguration, node.getId().toString()), false, now);
                nodes.save(node);
            } catch (DomainException error) { throw error; }
            catch (RuntimeException ignored) {
                throw new DomainException("PROXY_CREDENTIALS_UNAVAILABLE", "代理节点凭据不可用。", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
        nodes.flush();
    }

    private void synchronize(ProxySubscription subscription, List<ProxyNodeDefinition> definitions, Instant now) {
        Map<String, ProxyNode> previous = new HashMap<>();
        nodes.findAllBySourceSubscriptionId(subscription.getId()).forEach(node -> previous.put(node.getSourceNodeKey(), node));
        for (ProxyNodeDefinition definition : definitions) {
            ProxyNode node = previous.remove(definition.label());
            if (node == null) {
                UUID id = UUID.randomUUID();
                node = new ProxyNode(id, definition.label(), definition,
                        cipher.encrypt(definition.outboundJson(), id.toString()), true, now);
                node.attachSubscription(subscription.getId(), definition.label());
            } else {
                String oldConfiguration = cipher.decrypt(node.encryptedConfiguration(), node.getId().toString());
                if (!oldConfiguration.equals(definition.outboundJson())) {
                    node.update(definition.label(), definition,
                            cipher.encrypt(definition.outboundJson(), node.getId().toString()), node.isEnabled(), now);
                }
                // Reappearing nodes remain disabled until an administrator explicitly enables them.
                node.sourceMissing(false, now);
            }
            nodes.save(node);
        }
        previous.values().forEach(node -> { node.sourceMissing(true, now); nodes.save(node); });
        nodes.flush();
    }

    private ProxySubscription required(UUID id) {
        return subscriptions.findById(id).orElseThrow(() -> new DomainException("PROXY_SUBSCRIPTION_NOT_FOUND", "代理订阅不存在。", HttpStatus.NOT_FOUND));
    }
    private ProxySubscriptionResponse response(ProxySubscription subscription) {
        return ProxySubscriptionResponse.from(subscription, nodes.findAllBySourceSubscriptionId(subscription.getId()));
    }
    private static void checkVersion(ProxySubscription subscription, Long version) {
        if (version == null || version != subscription.getVersion())
            throw new DomainException("PROXY_SUBSCRIPTION_VERSION_CONFLICT", "代理订阅已变更，请刷新列表后重试。", HttpStatus.CONFLICT);
    }
    private static String cipherContext(UUID id) { return "subscription:" + id; }
}
