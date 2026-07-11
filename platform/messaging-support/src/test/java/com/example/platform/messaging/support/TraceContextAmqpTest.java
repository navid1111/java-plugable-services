package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves T009's E2E guarantee: a trace context injected by the producer survives the broker
 * hop and is extracted intact by the consumer, so producer, broker, and consumer share one
 * trace id and correlation id.
 */
@Testcontainers
class TraceContextAmqpTest {

    private static final String QUEUE = "trace.probe";

    @Container
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    static CachingConnectionFactory connectionFactory;
    static RabbitTemplate template;

    @BeforeAll
    static void setUp() {
        connectionFactory = new CachingConnectionFactory(rabbit.getHost(), rabbit.getAmqpPort());
        connectionFactory.setUsername(rabbit.getAdminUsername());
        connectionFactory.setPassword(rabbit.getAdminPassword());
        new RabbitAdmin(connectionFactory).declareQueue(new Queue(QUEUE, true));
        template = new RabbitTemplate(connectionFactory);
        template.setReceiveTimeout(5000);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void traceContextSurvivesTheBrokerHop() {
        TraceContext producer = new TraceContext(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "corr-42", "cause-7", "event-9", "post", "42", "user-1");

        MessageProperties props = new MessageProperties();
        AmqpTracing.inject(producer, props);
        template.send("", QUEUE, new Message("payload".getBytes(), props));

        Message received = template.receive(QUEUE);
        assertNotNull(received, "message delivered through broker");
        TraceContext consumer = AmqpTracing.extract(received.getMessageProperties());

        assertEquals(producer.traceId(), consumer.traceId(), "same trace joins producer and consumer");
        assertEquals("corr-42", consumer.correlationId());
        assertEquals("cause-7", consumer.causationId());
        assertEquals("event-9", consumer.eventId());
        assertEquals("user-1", consumer.userId());
        assertEquals("42", consumer.aggregateId());
    }
}
