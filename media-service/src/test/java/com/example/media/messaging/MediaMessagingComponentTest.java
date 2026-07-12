package com.example.media.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.media.MediaProcessingFailed;
import com.example.platform.messaging.post.PostSnapshot;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.platform.messaging.support.MessagingTopology;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.TargetProjection;
import com.example.platform.messaging.support.TargetProjectionRepository;
import com.example.platform.messaging.support.TransactionalEventWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "target.reconciliation.delay=3600000",
        "platform.messaging.outbox.poll-delay=100"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MediaMessagingComponentTest {
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String PROBE = "media-component.probe";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired ObjectMapper mapper;
    @Autowired TargetProjectionRepository targets;
    @Autowired InboxMessageRepository inbox;
    @Autowired OutboxMessageRepository outbox;
    @Autowired TransactionalEventWriter events;

    @BeforeEach
    void clean() {
        inbox.deleteAll();
        targets.deleteAll();
        outbox.deleteAll();
        rabbitAdmin.deleteQueue(PROBE);
    }

    @Test
    void duplicatePostDeliveryUpdatesTargetOnce() throws Exception {
        Instant now = Instant.now();
        EventEnvelope<PostSnapshot> event = EventEnvelope.fact(EventTypes.POST_CREATED_V1, 1,
                "tweeter-service", "post", "42", 1, UUID.randomUUID(), null, null,
                new PostSnapshot("42", USER_ID, "alice", "hello", "public", now, now));
        String json = mapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, EventTypes.POST_CREATED_V1, json);
        rabbitTemplate.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, EventTypes.POST_CREATED_V1, json);

        await(Duration.ofSeconds(10), () -> inbox.count() == 1 && targets.count() == 1);
        TargetProjection target = targets.findById(new TargetProjection.Key("post", "42")).orElseThrow();
        assertEquals(USER_ID, target.getOwnerUserId());
        assertTrue(target.isActive());
    }

    @Test
    void lifecycleOutboxPublishesPersistentlyAndCompletesOnlyAfterConfirm() throws Exception {
        Queue probe = new Queue(PROBE, false);
        TopicExchange exchange = new TopicExchange(MessagingTopology.EVENTS_EXCHANGE, true, false);
        rabbitAdmin.declareQueue(probe);
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(exchange)
                .with(EventTypes.MEDIA_PROCESSING_FAILED_V1));
        EventEnvelope<MediaProcessingFailed> event = EventEnvelope.fact(
                EventTypes.MEDIA_PROCESSING_FAILED_V1, 1, "media-service", "media-intent", "intent-1",
                1, UUID.randomUUID(), null, null,
                new MediaProcessingFailed("intent-1", "provider_failure", Instant.now()));

        events.write(event);

        org.springframework.amqp.core.Message delivery = rabbitTemplate.receive(PROBE, 10_000);
        assertNotNull(delivery, "media lifecycle event should reach the real broker");
        assertTrue(new String(delivery.getBody()).contains(EventTypes.MEDIA_PROCESSING_FAILED_V1));
        await(Duration.ofSeconds(5), () -> outbox.findById(event.eventId())
                .map(row -> row.getPublishedAt() != null).orElse(false));
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "asynchronous messaging flow did not converge");
    }
}
