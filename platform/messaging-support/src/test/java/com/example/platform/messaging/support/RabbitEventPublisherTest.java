package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real-broker proof for {@link RabbitEventPublisher}: a bound routing key is delivered
 * persistently and confirmed, while an unroutable event fails (mandatory + confirmed-only)
 * so the relay would retry rather than silently drop it.
 */
@Testcontainers
class RabbitEventPublisherTest {

    private static final String EXCHANGE = "platform.events.test";
    private static final String QUEUE = "test.post.created";

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static CachingConnectionFactory connectionFactory;
    static RabbitTemplate template;

    @BeforeAll
    static void setUp() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        connectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connectionFactory.setPublisherReturns(true);

        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue queue = new Queue(QUEUE, true);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("post.created.v1"));

        template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    private RabbitEventPublisher publisher() {
        return new RabbitEventPublisher(template, EXCHANGE, Duration.ofSeconds(5));
    }

    @Test
    void publishesConfirmedPersistentMessageToBoundRoutingKey() {
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "post", "42",
                "post.created.v1", 1, "{\"postId\":\"42\"}", Instant.now());

        assertDoesNotThrow(() -> publisher().publish(message));

        Message received = template.receive(QUEUE, 5000);
        assertNotNull(received, "message should have been routed to the bound queue");
        assertTrue(new String(received.getBody()).contains("\"postId\":\"42\""));
        assertEquals(MessageDeliveryMode.PERSISTENT,
                received.getMessageProperties().getReceivedDeliveryMode(),
                "delivery must be persistent");
    }

    @Test
    void unroutableEventFailsSoTheRelayRetries() {
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "post", "43",
                "no.binding.v1", 1, "{}", Instant.now());

        assertThrows(EventPublishException.class, () -> publisher().publish(message));
    }
}
