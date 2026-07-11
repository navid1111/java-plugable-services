package com.example.platform.messaging.support;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Record of an event already processed by a named consumer. Its composite primary key
 * {@code (consumer, eventId)} is the deduplication guarantee: a redelivered event cannot
 * be processed twice because the second insert violates the primary key. Consumers write
 * this row in the same transaction as their state change (the inbox pattern).
 *
 * <p>Markers are append-only, so {@link #isNew()} is always {@code true}: {@code save}
 * must issue a real {@code INSERT} (not a merge that could silently become an {@code UPDATE}
 * on a concurrent duplicate), so a redelivery always fails on the primary key.
 */
@Entity
@Table(name = "inbox_messages")
@IdClass(InboxMessage.Key.class)
public class InboxMessage implements Persistable<InboxMessage.Key> {

    @Id
    private String consumer;
    @Id
    private UUID eventId;
    private String eventType;
    private Instant processedAt;

    protected InboxMessage() {
    }

    public InboxMessage(String consumer, UUID eventId, String eventType, Instant processedAt) {
        this.consumer = consumer;
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = processedAt;
    }

    @Override
    @Transient
    public Key getId() {
        return new Key(consumer, eventId);
    }

    @Override
    @Transient
    public boolean isNew() {
        return true;
    }

    public String getConsumer() { return consumer; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getProcessedAt() { return processedAt; }

    /** Composite identity for {@link InboxMessage}. */
    public static class Key implements Serializable {
        private String consumer;
        private UUID eventId;

        public Key() {
        }

        public Key(String consumer, UUID eventId) {
            this.consumer = consumer;
            this.eventId = eventId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(consumer, key.consumer) && Objects.equals(eventId, key.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumer, eventId);
        }
    }
}
