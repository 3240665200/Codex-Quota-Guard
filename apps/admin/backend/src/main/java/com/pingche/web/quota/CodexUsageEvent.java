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

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "total_tokens", nullable = false)
    private long totalTokens;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(nullable = false, length = 48)
    private String source;

    protected CodexUsageEvent() { }

    public CodexUsageEvent(String id, UUID userId,
            long totalTokens, Instant occurredAt, Instant receivedAt, String source) {
        this.id = id;
        this.userId = userId;
        this.totalTokens = totalTokens;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.source = source;
    }

    public String getId() { return id; }
    public UUID getUserId() { return userId; }
    public long getTotalTokens() { return totalTokens; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getSource() { return source; }
}
