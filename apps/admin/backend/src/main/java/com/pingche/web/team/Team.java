package com.pingche.web.team;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "teams")
public class Team {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "five_hour_quota_percent", nullable = false)
    private int fiveHourQuotaPercent;

    @Column(name = "weekly_quota_percent", nullable = false)
    private int weeklyQuotaPercent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Team() {
    }

    public Team(
            String name,
            String description,
            Status status,
            int fiveHourQuotaPercent,
            int weeklyQuotaPercent,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.description = description;
        this.status = status;
        this.fiveHourQuotaPercent = fiveHourQuotaPercent;
        this.weeklyQuotaPercent = weeklyQuotaPercent;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }

    public int getFiveHourQuotaPercent() {
        return fiveHourQuotaPercent;
    }

    public int getWeeklyQuotaPercent() {
        return weeklyQuotaPercent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public void update(
            String name,
            String description,
            Status status,
            int fiveHourQuotaPercent,
            int weeklyQuotaPercent,
            Instant now
    ) {
        this.name = name;
        this.description = description;
        this.status = status;
        this.fiveHourQuotaPercent = fiveHourQuotaPercent;
        this.weeklyQuotaPercent = weeklyQuotaPercent;
        this.updatedAt = now;
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
