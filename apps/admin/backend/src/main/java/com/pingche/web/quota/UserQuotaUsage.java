package com.pingche.web.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_quota_usage")
public class UserQuotaUsage {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "codex_account_id", nullable = false)
    private UUID codexAccountId;

    @Column(name = "five_hour_used_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal fiveHourUsedPercent;

    @Column(name = "weekly_used_percent", nullable = false, precision = 5, scale = 2)
    private BigDecimal weeklyUsedPercent;

    @Column(name = "five_hour_resets_at")
    private Instant fiveHourResetsAt;

    @Column(name = "weekly_resets_at")
    private Instant weeklyResetsAt;

    @Column(name = "measured_at", nullable = false)
    private Instant measuredAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected UserQuotaUsage() {
    }

    public UserQuotaUsage(
            UUID userId,
            UUID codexAccountId,
            BigDecimal fiveHourUsedPercent,
            BigDecimal weeklyUsedPercent,
            Instant fiveHourResetsAt,
            Instant weeklyResetsAt,
            Instant measuredAt
    ) {
        this.userId = userId;
        this.codexAccountId = codexAccountId;
        update(
                codexAccountId,
                fiveHourUsedPercent,
                weeklyUsedPercent,
                fiveHourResetsAt,
                weeklyResetsAt,
                measuredAt
        );
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getCodexAccountId() {
        return codexAccountId;
    }

    public BigDecimal getFiveHourUsedPercent() {
        return fiveHourUsedPercent;
    }

    public BigDecimal getWeeklyUsedPercent() {
        return weeklyUsedPercent;
    }

    public Instant getFiveHourResetsAt() {
        return fiveHourResetsAt;
    }

    public Instant getWeeklyResetsAt() {
        return weeklyResetsAt;
    }

    public Instant getMeasuredAt() {
        return measuredAt;
    }

    public boolean belongsTo(UUID accountId) {
        return codexAccountId.equals(accountId);
    }

    public void update(
            UUID codexAccountId,
            BigDecimal fiveHourUsedPercent,
            BigDecimal weeklyUsedPercent,
            Instant fiveHourResetsAt,
            Instant weeklyResetsAt,
            Instant measuredAt
    ) {
        this.codexAccountId = codexAccountId;
        this.fiveHourUsedPercent = fiveHourUsedPercent;
        this.weeklyUsedPercent = weeklyUsedPercent;
        this.fiveHourResetsAt = fiveHourResetsAt;
        this.weeklyResetsAt = weeklyResetsAt;
        this.measuredAt = measuredAt;
    }
}
