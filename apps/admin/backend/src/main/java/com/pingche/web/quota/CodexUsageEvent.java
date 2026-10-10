package com.pingche.web.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A desktop-reported token event; it is not an independently verified upstream charge. */
@Entity
@Table(name = "codex_usage_events")
public class CodexUsageEvent {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = 48)
    private String source;

    @Column(length = 96)
    private String model;

    @Column(name = "reasoning_effort", length = 24)
    private String reasoningEffort;

    protected CodexUsageEvent() { }

    public CodexUsageEvent(String id, UUID userId,
            long totalTokens, Instant occurredAt, Instant receivedAt, String source) {
        this(id, userId, null, totalTokens, occurredAt, receivedAt, source);
    }

    public CodexUsageEvent(String id, UUID userId, UUID teamId,
            long totalTokens, Instant occurredAt, Instant receivedAt, String source) {
        this(id, userId, teamId, totalTokens, occurredAt, receivedAt, source, null, null);
    }

    public CodexUsageEvent(String id, UUID userId, UUID teamId,
            long totalTokens, Instant occurredAt, Instant receivedAt, String source,
            String model, String reasoningEffort) {
        this.id = id;
        this.userId = userId;
        this.teamId = teamId;
        this.totalTokens = totalTokens;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.source = source;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public String getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getTeamId() { return teamId; }
    public long getTotalTokens() { return totalTokens; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getSource() { return source; }
    public String getModel() { return model; }
    public String getReasoningEffort() { return reasoningEffort; }
}
