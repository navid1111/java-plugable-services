package com.example.postsearch.messaging;

import java.time.Instant;
import java.util.UUID;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.InboxIdempotency;
import com.example.postsearch.service.PostSearchService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class PostLifecycleEventConsumer {
    private final ObjectMapper mapper;
    private final InboxIdempotency inbox;
    private final PostSearchService search;

    public PostLifecycleEventConsumer(ObjectMapper mapper, InboxIdempotency inbox,
            PostSearchService search) {
        this.mapper = mapper;
        this.inbox = inbox;
        this.search = search;
    }

    @RabbitListener(queues = PostProjectionMessagingConfiguration.CONSUMER)
    public void receive(String json) {
        process(json);
    }

    public boolean process(String json) {
        JsonNode event = mapper.readTree(json);
        UUID eventId = UUID.fromString(requiredText(event, "eventId"));
        String eventType = requiredText(event, "eventType");
        long version = event.path("aggregateVersion").asLong();
        if (version < 1) throw new IllegalArgumentException("aggregateVersion must be positive");
        JsonNode payload = event.path("payload");

        return inbox.process(PostProjectionMessagingConfiguration.CONSUMER, eventId, eventType,
                () -> apply(eventType, version, payload));
    }

    private void apply(String eventType, long version, JsonNode payload) {
        String postId = requiredText(payload, "postId");
        switch (eventType) {
            case EventTypes.POST_CREATED_V1, EventTypes.POST_UPDATED_V1 ->
                    search.applyPostSnapshot(postId, optionalText(payload, "authorUserId"), requiredText(payload, "authorUsername"),
                            requiredText(payload, "content"),
                            Instant.parse(requiredText(payload, "createdAt")), version);
            case EventTypes.POST_DELETED_V1 ->
                    search.deletePostProjection(postId, version,
                            Instant.parse(requiredText(payload, "deletedAt")));
            default -> throw new IllegalArgumentException("unsupported event type: " + eventType);
        }
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
    private String optionalText(JsonNode node, String field) {
        String value = node.path(field).asText(); return value == null || value.isBlank() ? null : value;
    }
}
