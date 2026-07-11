package com.example.platform.messaging.support;

import java.time.Instant;
import java.util.UUID;
import com.example.platform.messaging.EventTypes;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class PostTargetEventProcessor {
    private final String consumer;
    private final ObjectMapper mapper;
    private final InboxIdempotency inbox;
    private final TargetProjectionStore targets;
    public PostTargetEventProcessor(String consumer, ObjectMapper mapper,
            InboxIdempotency inbox, TargetProjectionStore targets) {
        this.consumer = consumer; this.mapper = mapper; this.inbox = inbox; this.targets = targets;
    }
    public boolean process(String json) {
        JsonNode event = mapper.readTree(json);
        UUID id = UUID.fromString(required(event, "eventId"));
        String type = required(event, "eventType");
        long version = event.path("aggregateVersion").asLong();
        JsonNode payload = event.path("payload");
        return inbox.process(consumer, id, type, () -> {
            if (EventTypes.POST_CREATED_V1.equals(type) || EventTypes.POST_UPDATED_V1.equals(type)) {
                targets.apply("post", required(payload, "postId"), required(payload, "authorUsername"),
                        version, true, Instant.parse(required(payload, "updatedAt")));
            } else if (EventTypes.POST_DELETED_V1.equals(type)) {
                targets.apply("post", required(payload, "postId"), null, version, false,
                        Instant.parse(required(payload, "deletedAt")));
            } else throw new IllegalArgumentException("unsupported target event: " + type);
        });
    }
    private String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
