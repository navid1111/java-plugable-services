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
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves T007 against a real broker: the durable exchange/quorum-queue/retry/DLQ topology
 * declares successfully, routes bound events to the work queue and dead-letters to the DLQ,
 * and — the key guarantee — recreates fully after the broker loses its definitions.
 */
@Testcontainers
class MessagingTopologyTest {

    private static final String CONSUMER = "post-search";
    private static final MessagingTopology.ConsumerSpec SPEC = new MessagingTopology.ConsumerSpec(
            CONSUMER,
            List.of("post.created.v1", "post.updated.v1", "post.deleted.v1"),
            Duration.ofSeconds(5),
            1000);

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static CachingConnectionFactory connectionFactory;
    static RabbitAdmin admin;
    static RabbitTemplate template;

    @BeforeAll
    static void setUp() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        admin = new RabbitAdmin(connectionFactory);
        template = new RabbitTemplate(connectionFactory);
        template.setReceiveTimeout(5000);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private static void declareAll(Declarables declarables) {
        for (Declarable d : declarables.getDeclarables()) {
            if (d instanceof Exchange exchange) {
                admin.declareExchange(exchange);
            } else if (d instanceof Queue queue) {
                admin.declareQueue(queue);
            } else if (d instanceof Binding binding) {
                admin.declareBinding(binding);
            }
        }
    }

    private static void declareTopology() {
        declareAll(MessagingTopology.exchanges());
        declareAll(MessagingTopology.forConsumer(SPEC));
    }

    private boolean queueExists(String name) {
        return admin.getQueueProperties(name) != null;
    }

    @Test
    void topologyDeclaresRoutesAndRecreatesAfterBrokerReset() {
        declareTopology();

        assertTrue(queueExists(CONSUMER), "work queue declared");
        assertTrue(queueExists(CONSUMER + ".retry"), "retry queue declared");
        assertTrue(queueExists(CONSUMER + ".dlq"), "dead-letter queue declared");

        assertRoutesToWorkQueue();
        assertDeadLettersToDlq();

        // Simulate a broker data reset: definitions are gone.
        admin.deleteQueue(CONSUMER);
        admin.deleteQueue(CONSUMER + ".retry");
        admin.deleteQueue(CONSUMER + ".dlq");
        admin.deleteExchange(MessagingTopology.EVENTS_EXCHANGE);
        admin.deleteExchange(MessagingTopology.DEAD_LETTER_EXCHANGE);
        assertFalse(queueExists(CONSUMER), "work queue removed by reset");

        // The same declaration pass rebuilds everything.
        declareTopology();
        assertTrue(queueExists(CONSUMER), "work queue recreated");
        assertTrue(queueExists(CONSUMER + ".retry"), "retry queue recreated");
        assertTrue(queueExists(CONSUMER + ".dlq"), "dead-letter queue recreated");
        assertRoutesToWorkQueue();
    }

    private void assertRoutesToWorkQueue() {
        template.convertAndSend(MessagingTopology.EVENTS_EXCHANGE, "post.created.v1", "event-body");
        Object body = template.receiveAndConvert(CONSUMER);
        assertEquals("event-body", body, "bound event routed to the work queue");
    }

    private void assertDeadLettersToDlq() {
        // Publishing to the DLX with the work-queue routing key must land in the DLQ.
        template.convertAndSend(MessagingTopology.DEAD_LETTER_EXCHANGE, CONSUMER, "dead-body");
        Object body = template.receiveAndConvert(CONSUMER + ".dlq");
        assertEquals("dead-body", body, "dead-lettered message routed to the DLQ");
    }
}
