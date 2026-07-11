package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves T008 end-to-end on a real broker: a transient classification re-homes the delivery
 * to the retry queue, a permanent classification re-homes it to the dead-letter queue, and a
 * success re-homes nothing. Combined with {@link EventErrorClassifierTest}, this shows each
 * error class reaches its expected ack/retry/DLQ outcome.
 */
@Testcontainers
class EventOutcomeRoutingTest {

    private static final String CONSUMER = "comment-service";
    private static final MessagingTopology.ConsumerSpec SPEC = new MessagingTopology.ConsumerSpec(
            CONSUMER, List.of("post.created.v1"), Duration.ofSeconds(30), 1000);

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static CachingConnectionFactory connectionFactory;
    static RabbitTemplate template;
    static EventOutcomeRouter router;
    static EventErrorClassifier classifier = new EventErrorClassifier(3);

    @BeforeAll
    static void setUp() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        declareAll(admin, MessagingTopology.exchanges());
        declareAll(admin, MessagingTopology.forConsumer(SPEC));
        template = new RabbitTemplate(connectionFactory);
        router = new EventOutcomeRouter(template);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static void declareAll(RabbitAdmin admin, Declarables declarables) {
        for (Declarable d : declarables.getDeclarables()) {
            if (d instanceof Exchange e) admin.declareExchange(e);
            else if (d instanceof Queue q) admin.declareQueue(q);
            else if (d instanceof Binding b) admin.declareBinding(b);
        }
    }

    private Message body(String text) {
        return MessageBuilder.withBody(text.getBytes()).build();
    }

    private String receive(String queue, long timeoutMs) {
        Message m = template.receive(queue, timeoutMs);
        return m == null ? null : new String(m.getBody());
    }

    @Test
    void transientErrorRoutesToRetryQueue() {
        MessageOutcome outcome = classifier.classify(new TransientProcessingException("db"), 1);
        router.route(CONSUMER, outcome, body("retry-me"));
        assertEquals("retry-me", receive(CONSUMER + ".retry", 5000));
    }

    @Test
    void permanentErrorRoutesToDeadLetterQueue() {
        MessageOutcome outcome = classifier.classify(new InvalidEventSchemaException("bad"), 1);
        router.route(CONSUMER, outcome, body("poison"));
        assertEquals("poison", receive(CONSUMER + ".dlq", 5000));
    }

    @Test
    void ackRoutesNowhere() {
        router.route(CONSUMER, MessageOutcome.ACK, body("done"));
        assertNull(receive(CONSUMER + ".retry", 300), "ack must not republish to retry");
        assertNull(receive(CONSUMER + ".dlq", 300), "ack must not republish to dlq");
    }
}
