package com.example.whatsapp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.whatsapp.service.ChatService;

/**
 * End-to-end outbox flow for chat against real PostgreSQL and RabbitMQ (T049 coverage for the
 * chat flow): with the relay enabled, a sent message's outbox row is drained and the
 * {@code chat.message-created.v1} external-reaction event is published — confirmed, persistent
 * — to the shared exchange and delivered to a bound queue, while DB delivery is unaffected.
 */
@SpringBootTest(properties = "chat.messaging.outbox.delay-ms=200")
@Testcontainers
class ChatEventBrokerTest {

    private static final String EXCHANGE = "platform.events.v1";
    private static final String PROBE_QUEUE = "chat-broker-test.probe";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired ChatService chatService;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbc;

    @Test
    void sentMessageIsPublishedToTheBroker() {
        Queue probe = new Queue(PROBE_QUEUE, false);
        TopicExchange events = new TopicExchange(EXCHANGE, true, false);
        rabbitAdmin.declareQueue(probe);
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(events).with("chat.message-created.v1"));
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(events).with("chat.message-read.v1"));

        var chat = chatService.createChat("alice", "room", List.of("bob"));
        var delivery = chatService.sendMessage("alice", chat.id(), "hello bob");
        long messageId = delivery.message().id();

        Message received = rabbitTemplate.receive(PROBE_QUEUE, 10_000);
        assertNotNull(received, "chat.message-created.v1 should be delivered to the broker");
        String body = new String(received.getBody());
        assertTrue(body.contains("chat.message-created.v1"), "envelope carries the event type");
        assertTrue(body.contains("\"messageId\":" + messageId), body);

        long deadline = System.currentTimeMillis() + 5_000;
        long unpublished = Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            unpublished = jdbc.queryForObject(
                    "select count(*) from outbox_messages where published_at is null", Long.class);
            if (unpublished == 0) {
                break;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertEquals(0L, unpublished, "outbox drains after confirmed publish");
    }
}
