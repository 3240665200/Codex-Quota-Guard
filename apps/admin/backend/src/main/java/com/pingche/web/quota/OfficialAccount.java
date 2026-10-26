package com.pingche.web.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "official_accounts")
public class OfficialAccount {
    @Column(name = "budget_usd", precision = 30, scale = 12) private java.math.BigDecimal budgetUsd;
    public java.math.BigDecimal getBudgetUsd() { return budgetUsd; }
    public void setBudgetUsd(java.math.BigDecimal value) { budgetUsd = value; }
    @Id private UUID id;
    @Column(nullable = false, unique = true, length = 120) private String label;
    @Column(name = "estimated_total_tokens", nullable = false) private long estimatedTotalTokens;
    @Column(name = "reporter_user_id") private UUID reporterUserId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private long version;

    protected OfficialAccount() { }

    public OfficialAccount(String label, long estimatedTotalTokens, UUID reporterUserId, Instant now) {
        this.id = UUID.randomUUID();
        this.label = label;
        this.estimatedTotalTokens = estimatedTotalTokens;
        this.reporterUserId = reporterUserId;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String label, long estimatedTotalTokens, UUID reporterUserId, Instant now) {
        this.label = label;
        this.estimatedTotalTokens = estimatedTotalTokens;
        this.reporterUserId = reporterUserId;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getLabel() { return label; }
    public long getEstimatedTotalTokens() { return estimatedTotalTokens; }
    public UUID getReporterUserId() { return reporterUserId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
