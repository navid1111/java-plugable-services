package com.example.platform.messaging;

import static org.junit.jupiter.api.Assertions.*;
import com.example.platform.messaging.post.PostSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

class EventEnvelopeTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void serializesStableEnvelopeAndPayload() {
        var snapshot = new PostSnapshot("42", "user-1", "alice", "hello", "PUBLIC", Instant.parse("2026-07-11T00:00:00Z"), Instant.parse("2026-07-11T00:00:00Z"));
        var event = new EventEnvelope<>(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                EventTypes.POST_CREATED_V1, 1, Instant.parse("2026-07-11T00:00:00Z"), "tweeter-service",
                "post", "42", 1, UUID.fromString("00000000-0000-0000-0000-000000000002"), null, null, snapshot);
        var json = mapper.readTree(mapper.writeValueAsString(event));
        assertEquals("post.created.v1", json.get("eventType").asText());
        assertEquals("alice", json.get("payload").get("authorUsername").asText());
        assertFalse(json.has("password"));
    }

    @Test void rejectsInvalidMetadata() {
        assertThrows(IllegalArgumentException.class, () -> new EventEnvelope<>(UUID.randomUUID(), "", 1,
                Instant.now(), "producer", "post", "1", 1, UUID.randomUUID(), null, null, new Object()));
        assertThrows(IllegalArgumentException.class, () -> EventEnvelope.fact("post.created.v1", 1,
                "producer", "post", "1", 0, null, null, null, new Object()));
    }
}
