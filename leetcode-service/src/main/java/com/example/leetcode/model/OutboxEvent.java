package com.example.leetcode.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id private UUID id;
    private String aggregateId;
    private String eventType;
    @Column(columnDefinition = "TEXT") private String payload;
    private Instant occurredAt;
    private Instant publishedAt;
    private int attempts;
    @Column(columnDefinition = "TEXT") private String lastError;
    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public String getAggregateId() { return aggregateId; } public void setAggregateId(String v) { aggregateId=v; }
    public String getEventType() { return eventType; } public void setEventType(String v) { eventType=v; }
    public String getPayload() { return payload; } public void setPayload(String v) { payload=v; }
    public Instant getOccurredAt() { return occurredAt; } public void setOccurredAt(Instant v) { occurredAt=v; }
    public Instant getPublishedAt() { return publishedAt; } public void setPublishedAt(Instant v) { publishedAt=v; }
    public int getAttempts() { return attempts; } public void setAttempts(int v) { attempts=v; }
    public String getLastError() { return lastError; } public void setLastError(String v) { lastError=v; }
}
