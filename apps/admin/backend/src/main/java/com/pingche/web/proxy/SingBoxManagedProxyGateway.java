package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class SingBoxManagedProxyGateway implements ManagedProxyGateway {
    private final ProxyNodeRepository nodes;
    private final ProxySecretCipher cipher;
    private final ProxyEndpointPolicy endpoints;
    private final SingBoxConfiguration configuration;
    private final SingBoxProcessRunner runner;
    private final boolean allowPrivate;

    public SingBoxManagedProxyGateway(ProxyNodeRepository nodes, ProxySecretCipher cipher, ProxyEndpointPolicy endpoints,
            SingBoxConfiguration configuration, SingBoxProcessRunner runner,
            @Value("${MANAGED_PROXY_ALLOW_PRIVATE_NODES:false}") boolean allowPrivate) {
        this.nodes = nodes; this.cipher = cipher; this.endpoints = endpoints; this.configuration = configuration;
        this.runner = runner; this.allowPrivate = allowPrivate;
    }

    @Override public ProxyLease open(UUID nodeId) {
        return open(nodeId, Purpose.OFFICIAL_SESSION);
    }

    @Override public ProxyLease open(UUID nodeId, Purpose purpose) {
        if (purpose == null) throw unavailable();
        if (nodeId == null && purpose == Purpose.BROWSER_LOGIN) return runner.open(configuration::buildDirectBrowser, purpose);
        if (nodeId == null) throw unavailable();
        ProxyNode node = nodes.findById(nodeId).orElseThrow(this::unavailable);
        if (!node.isEnabled()) throw unavailable();
        String plaintext = cipher.decrypt(node.encryptedConfiguration(), node.getId().toString());
        ProxyNodeDefinition definition = new ProxyNodeDefinition(node.getLabel(), node.getProtocol(), node.getHost(),
                node.getPort(), plaintext, node.hasCredentials());
        InetAddress pinned = endpoints.resolve(node.getHost(), allowPrivate);
        return runner.open(port -> configuration.build(definition, pinned, port, purpose), purpose);
    }

    private DomainException unavailable() {
        return new DomainException("PROXY_NODE_UNAVAILABLE", "绑定的代理节点不存在或已停用，未回退直连。", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
