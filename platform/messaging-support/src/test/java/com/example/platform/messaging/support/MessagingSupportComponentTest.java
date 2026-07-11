package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Reusable component test for the shared outbox/inbox against a real PostgreSQL:
 * Flyway builds the schema, and the test exercises drain selection, retention purge,
 * and the primary-key deduplication guarantee the inbox depends on.
 */
@SpringBootTest
@Testcontainers
class MessagingSupportComponentTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OutboxMessageRepository outbox;
    @Autowired InboxMessageRepository inbox;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table outbox_messages");
        jdbc.execute("truncate table inbox_messages");
    }

    @Test
    void outboxDrainSelectionAndRetention() {
        Instant now = Instant.now();
        OutboxMessage msg = new OutboxMessage(UUID.randomUUID(), "post", "42",
                "post.created.v1", 1, "{\"postId\":\"42\"}", now.minusSeconds(5));
        outbox.save(msg);

        var due = outbox.findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
                now, PageRequest.of(0, 10));
        assertEquals(1, due.size(), "unpublished, due message should be a drain candidate");

        // Publish it long ago, then let retention purge already-published rows.
        msg.setPublishedAt(now.minus(Duration.ofDays(30)));
        outbox.save(msg);
        assertTrue(outbox.findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
                now, PageRequest.of(0, 10)).isEmpty(), "published message is no longer a candidate");

        int purged = outbox.deletePublishedBefore(now.minus(Duration.ofDays(7)));
        assertEquals(1, purged);
        assertEquals(0, outbox.count());
    }

    @Test
    void inboxDeduplicatesByPrimaryKey() {
        String consumer = "post-search";
        UUID eventId = UUID.randomUUID();
        inbox.save(new InboxMessage(consumer, eventId, "post.created.v1", Instant.now()));
        assertTrue(inbox.existsByConsumerAndEventId(consumer, eventId));

        // A redelivery of the same event for the same consumer must be rejected by the DB.
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "insert into inbox_messages(consumer,event_id,event_type,processed_at) values (?,?,?,?)",
                consumer, eventId, "post.created.v1", Timestamp.from(Instant.now())));

        // A different consumer processing the same event is allowed (independent progress).
        assertDoesNotThrow(() -> inbox.save(
                new InboxMessage("bff", eventId, "post.created.v1", Instant.now())));
    }
}
