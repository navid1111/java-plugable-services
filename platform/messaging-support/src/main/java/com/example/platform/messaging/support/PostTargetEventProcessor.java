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
    private final TargetLifecycleObserver observer;
    public interface TargetLifecycleObserver {
        void changed(String targetType, String targetId, long version, boolean active, Instant when);
    }
    public PostTargetEventProcessor(String consumer, ObjectMapper mapper,
            InboxIdempotency inbox, TargetProjectionStore targets) {
        this(consumer, mapper, inbox, targets, (type, id, version, active, when) -> {});
    }
    public PostTargetEventProcessor(String consumer, ObjectMapper mapper,
            InboxIdempotency inbox, TargetProjectionStore targets, TargetLifecycleObserver observer) {
        this.consumer = consumer; this.mapper = mapper; this.inbox = inbox; this.targets = targets;
        this.observer = observer;
    }
    public boolean process(String json) {
        JsonNode event = mapper.readTree(json);
        UUID id = UUID.fromString(required(event, "eventId"));
        String type = required(event, "eventType");
        long version = event.path("aggregateVersion").asLong();
        JsonNode payload = event.path("payload");
        return inbox.process(consumer, id, type, () -> {
            if (EventTypes.POST_CREATED_V1.equals(type) || EventTypes.POST_UPDATED_V1.equals(type)) {
                String postId = required(payload, "postId");
                Instant when = Instant.parse(required(payload, "updatedAt"));
                targets.apply("post", postId, required(payload, "authorUserId"),
                        required(payload, "authorUsername"), version, true, when);
                observer.changed("post", postId, version, true, when);
            } else if (EventTypes.POST_DELETED_V1.equals(type)) {
                String postId = required(payload, "postId");
                Instant when = Instant.parse(required(payload, "deletedAt"));
                targets.apply("post", postId, null, null, version, false, when);
                observer.changed("post", postId, version, false, when);
            } else throw new IllegalArgumentException("unsupported target event: " + type);
        });
    }
    private String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
