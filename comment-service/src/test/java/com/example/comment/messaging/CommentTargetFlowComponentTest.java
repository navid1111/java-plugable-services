package com.example.comment.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.post.PostSnapshot;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.platform.messaging.support.MessagingTopology;
import com.example.platform.messaging.support.TargetProjection;
import com.example.platform.messaging.support.TargetProjectionRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        "target.reconciliation.delay=3600000"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CommentTargetFlowComponentTest {
    private static final String USER_ID = "550e8400-e29b-41d4-a716-446655440000";

    @Container @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Container @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired ObjectMapper mapper;
    @Autowired TargetProjectionRepository targets;
    @Autowired InboxMessageRepository inbox;

    @BeforeEach
    void clean() {
        inbox.deleteAll();
        targets.deleteAll();
    }

    @Test
    void duplicatePostEventCreatesOneEffectiveTargetAndOneInboxRecord() throws Exception {
        Instant now = Instant.now();
        EventEnvelope<PostSnapshot> event = EventEnvelope.fact(EventTypes.POST_CREATED_V1, 1,
                "tweeter-service", "post", "42", 1, UUID.randomUUID(), null, null,
                new PostSnapshot("42", USER_ID, "alice", "hello", "public", now, now));
        String json = mapper.writeValueAsString(event);

        rabbitTemplate.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, EventTypes.POST_CREATED_V1, json);
        rabbitTemplate.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, EventTypes.POST_CREATED_V1, json);

        await(Duration.ofSeconds(10), () -> inbox.count() == 1 && targets.count() == 1);
        TargetProjection target = targets.findById(new TargetProjection.Key("post", "42")).orElseThrow();
        assertTrue(target.isActive());
        assertEquals(USER_ID, target.getOwnerUserId());
        assertEquals(1, inbox.count());
    }

    private static void await(Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) Thread.sleep(100);
        assertTrue(condition.getAsBoolean(), "asynchronous target flow did not converge");
    }
}
