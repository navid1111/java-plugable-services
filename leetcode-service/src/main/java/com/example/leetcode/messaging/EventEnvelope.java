package com.example.leetcode.messaging;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope<T>(UUID eventId, String eventType, int eventVersion, Instant occurredAt,
                               String producer, UUID correlationId, UUID causationId, T payload) {
    public static <T> EventEnvelope<T> create(String type, String producer, T payload) {
        UUID id = UUID.randomUUID();
        return new EventEnvelope<>(id, type, 1, Instant.now(), producer, id, null, payload);
    }
}
