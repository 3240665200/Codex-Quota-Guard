package com.pingche.web.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "official_quota_snapshots")
public class OfficialQuotaSnapshotEntity {

    @Id
    @Column(name = "user_id")
    private UUID userId;
    @Column(name = "plan_type", length = 40)
    private String planType;
    @Column(name = "windows_json", nullable = false, length = 8192)
    private String windowsJson;
    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected OfficialQuotaSnapshotEntity() { }

    public OfficialQuotaSnapshotEntity(UUID userId, String planType, String windowsJson,
            Instant fetchedAt, Instant receivedAt) {
        this.userId = userId;
        this.planType = planType;
        this.windowsJson = windowsJson;
        this.fetchedAt = fetchedAt;
        this.receivedAt = receivedAt;
    }

    public UUID getUserId() { return userId; }
    public String getPlanType() { return planType; }
    public String getWindowsJson() { return windowsJson; }
    public Instant getFetchedAt() { return fetchedAt; }
    public Instant getReceivedAt() { return receivedAt; }
}
