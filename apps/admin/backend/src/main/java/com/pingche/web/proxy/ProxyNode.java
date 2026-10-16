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
    @Column(name = "source_subscription_id") private UUID sourceSubscriptionId;
    @Column(name = "source_node_key", length = 120) private String sourceNodeKey;
    @Column(name = "source_missing", nullable = false) private boolean sourceMissing;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "egress_ip", length = 45) private String egressIp;
    @Column(length = 120) private String country;
    @Column(length = 120) private String region;
    @Column(length = 120) private String city;
    @Column(name = "tested_at") private Instant testedAt;
    @Column(name = "test_status", nullable = false, length = 24) private String testStatus = "UNTESTED";
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
            egressIp = null; country = null; region = null; city = null; testedAt = null; testStatus = "UNTESTED";
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
    public UUID getSourceSubscriptionId() { return sourceSubscriptionId; }
    public String getSourceNodeKey() { return sourceNodeKey; }
    public boolean isSourceMissing() { return sourceMissing; }
    void setEnabled(boolean enabled, Instant now) { this.enabled = enabled; this.updatedAt = now; }
    void attachSubscription(UUID id, String key) { this.sourceSubscriptionId = id; this.sourceNodeKey = key; }
    void sourceMissing(boolean missing, Instant now) {
        if (this.sourceMissing != missing) { this.sourceMissing = missing; this.updatedAt = now; }
        if (missing && enabled) setEnabled(false, now);
    }
    void detachSubscription(Instant now) {
        this.sourceSubscriptionId = null; this.sourceNodeKey = null; this.sourceMissing = false; this.updatedAt = now;
    }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
    void recordTest(ProxyNodeProbe.Result result, Instant now) {
        testedAt = now;
        testStatus = result == null ? "FAILED" : "CONNECTED";
        egressIp = result == null ? null : result.egressIp();
        country = result == null ? null : result.country();
        region = result == null ? null : result.region();
        city = result == null ? null : result.city();
    }
    public String getEgressIp() { return egressIp; }
    public String getCountry() { return country; }
    public String getRegion() { return region; }
    public String getCity() { return city; }
    public Instant getTestedAt() { return testedAt; }
    public String getTestStatus() { return testStatus; }
    @Override public String toString() { return "ProxyNode[id=" + id + "]"; }
}
