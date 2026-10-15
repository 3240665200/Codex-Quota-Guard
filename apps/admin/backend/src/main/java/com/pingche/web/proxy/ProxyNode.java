package com.pingche.web.proxy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "managed_proxy_nodes")
public class ProxyNode {
    @Id private UUID id;
    @Column(nullable = false, length = 120) private String label;
    @Column(nullable = false, length = 24) private String protocol;
    @Column(nullable = false, length = 253) private String host;
    @Column(nullable = false) private int port;
    @Column(name = "config_cipher", nullable = false, columnDefinition = "text") private String configCipher;
    @Column(name = "has_credentials", nullable = false) private boolean hasCredentials;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected ProxyNode() { }
    ProxyNode(UUID id, String label, ProxyNodeDefinition definition, String encrypted, boolean enabled, Instant now) {
        this.id = id; this.createdAt = now;
        update(label, definition, encrypted, enabled, now);
    }
    void update(String label, ProxyNodeDefinition definition, String encrypted, boolean enabled, Instant now) {
        this.label = label; this.enabled = enabled; this.updatedAt = now;
        if (definition != null) {
            protocol = definition.protocol(); host = definition.host(); port = definition.port();
            hasCredentials = definition.hasCredentials(); configCipher = encrypted;
        }
    }
    public UUID getId() { return id; }
    public String getLabel() { return label; }
    public String getProtocol() { return protocol; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    String encryptedConfiguration() { return configCipher; }
    public boolean hasCredentials() { return hasCredentials; }
    public boolean isEnabled() { return enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    @Override public String toString() { return "ProxyNode[id=" + id + "]"; }
}
