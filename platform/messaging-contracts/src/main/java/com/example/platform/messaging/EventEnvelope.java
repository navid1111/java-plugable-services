package com.example.platform.messaging;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        UUID correlationId,
        UUID causationId,
        String traceparent,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        requireText(eventType, "eventType");
        if (eventVersion < 1) throw new IllegalArgumentException("eventVersion must be positive");
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(producer, "producer");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        if (aggregateVersion < 1) throw new IllegalArgumentException("aggregateVersion must be positive");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
    }

    public static <T> EventEnvelope<T> fact(String eventType, int eventVersion, String producer,
            String aggregateType, String aggregateId, long aggregateVersion,
            UUID correlationId, UUID causationId, String traceparent, T payload) {
        return new EventEnvelope<>(UUID.randomUUID(), eventType, eventVersion, Instant.now(), producer,
                aggregateType, aggregateId, aggregateVersion,
                correlationId == null ? UUID.randomUUID() : correlationId, causationId, traceparent, payload);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
