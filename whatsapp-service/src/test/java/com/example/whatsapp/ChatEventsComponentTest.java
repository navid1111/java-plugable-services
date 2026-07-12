package com.example.whatsapp;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.whatsapp.repository.MessageRepository;
import com.example.whatsapp.service.ChatService;

/**
 * Proves T046: chat events are emitted for external reactions only, and the broker being
 * unavailable (relay disabled here) never blocks message persistence or later event delivery.
 * A sent message is stored (its DB/WebSocket delivery path is unaffected) and its event is
 * captured in the outbox to publish once the broker returns; reads are emitted idempotently.
 */
@SpringBootTest(properties = "chat.messaging.relay-enabled=false")
@Testcontainers
class ChatEventsComponentTest {
    private static final String ALICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    private static final String BOB_ID = "550e8400-e29b-41d4-a716-446655440001";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired ChatService chatService;
    @Autowired MessageRepository messages;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table outbox_messages");
    }

    private long count(String eventType) {
        return jdbc.queryForObject(
                "select count(*) from outbox_messages where event_type = ?", Long.class, eventType);
    }

    @Test
    void brokerOutageStillPersistsMessageAndCapturesEvent() {
        var chat = chatService.createChat(ALICE_ID, "alice", "room",
                List.of(new ChatService.UserRef(BOB_ID, "bob")));
        var delivery = chatService.sendMessage(ALICE_ID, "alice-renamed", chat.id(), "hello bob");

        // Delivery source of truth (DB) is intact even though the broker is "down".
        assertTrue(messages.findById(delivery.message().id()).isPresent(),
                "message persists regardless of broker availability");
        assertEquals(List.of(BOB_ID), delivery.recipientUserIds());
        assertTrue(chatService.findMyChats(ALICE_ID).stream().anyMatch(view -> view.id().equals(chat.id())),
                "rename does not change chat membership");

        // The event is safely captured for later delivery once the relay/broker recovers.
        assertEquals(1, count("chat.message-created.v1"), "created event captured in outbox");
    }

    @Test
    void readEventIsEmittedOncePerAck() {
        var chat = chatService.createChat(ALICE_ID, "alice", "room",
                List.of(new ChatService.UserRef(BOB_ID, "bob")));
        var delivery = chatService.sendMessage(ALICE_ID, "alice", chat.id(), "hi");
        long messageId = delivery.message().id();

        chatService.ack(BOB_ID, "bob-renamed", messageId);
        chatService.ack(BOB_ID, "bob-renamed", messageId); // redelivered ack must not emit again

        assertEquals(1, count("chat.message-read.v1"), "read emitted once despite repeated acks");
    }
}
