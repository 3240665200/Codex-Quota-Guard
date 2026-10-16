package com.pingche.web.proxy;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "managed_proxy_subscriptions")
public class ProxySubscription {
    @Id private UUID id;
    @Column(nullable = false, length = 120) private String label;
    @Column(name = "url_cipher", nullable = false, columnDefinition = "text") private String urlCipher;
    @Column(name = "source_host", nullable = false, length = 253) private String sourceHost;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "refreshed_at", nullable = false) private Instant refreshedAt;
    @Version @Column(nullable = false) private long version;

    protected ProxySubscription() { }
    ProxySubscription(UUID id, String label, String encryptedUrl, String host, Instant now) {
        this.id = id; this.label = label; this.urlCipher = encryptedUrl; this.sourceHost = host;
        this.status = "READY"; this.createdAt = now; this.updatedAt = now; this.refreshedAt = now;
    }
    void edit(String label, String encryptedUrl, String host, Instant now) {
        this.label = label; this.updatedAt = now;
        if (encryptedUrl != null) { this.urlCipher = encryptedUrl; this.sourceHost = host; this.status = "URL_UPDATED"; }
    }
    void refreshed(Instant now) { this.refreshedAt = now; this.updatedAt = now; this.status = "READY"; }
    public UUID getId() { return id; }
    public String getLabel() { return label; }
    String encryptedUrl() { return urlCipher; }
    public String getSourceHost() { return sourceHost; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getRefreshedAt() { return refreshedAt; }
    public long getVersion() { return version; }
    @Override public String toString() { return "ProxySubscription[id=" + id + "]"; }
}
