package com.example.platform.messaging.support;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A committed domain fact awaiting publication. Written in the same local transaction
 * as the state change it describes (the outbox pattern) so a fact is never lost and a
 * publish never happens for work that rolled back. {@code payload} holds the fully
 * serialized {@link com.example.platform.messaging.EventEnvelope}. Claim columns support
 * safe concurrent draining by competing publisher instances (see T005).
 */
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {

    @Id
    private UUID id;
    private String aggregateType;
    private String aggregateId;
    private String eventType;
    private int eventVersion;
    @Column(columnDefinition = "text")
    private String payload;
    private Instant createdAt;
    private Instant availableAt;
    private int attempts;
    private Instant publishedAt;
    @Column(columnDefinition = "text")
    private String lastError;
    private String claimedBy;
    private Instant claimedUntil;

    protected OutboxMessage() {
    }

    public OutboxMessage(UUID id, String aggregateType, String aggregateId, String eventType,
            int eventVersion, String payload, Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.eventVersion = eventVersion;
        this.payload = payload;
        this.createdAt = createdAt;
        this.availableAt = createdAt;
    }

    public UUID getId() { return id; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public String getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAvailableAt() { return availableAt; }
    public void setAvailableAt(Instant availableAt) { this.availableAt = availableAt; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getClaimedBy() { return claimedBy; }
    public void setClaimedBy(String claimedBy) { this.claimedBy = claimedBy; }
    public Instant getClaimedUntil() { return claimedUntil; }
    public void setClaimedUntil(Instant claimedUntil) { this.claimedUntil = claimedUntil; }
}
