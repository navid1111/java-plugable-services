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
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

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
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired TargetProjectionRepository targets;

    @BeforeEach
    void clean() {
        jdbc.execute("truncate table outbox_messages");
        jdbc.execute("truncate table inbox_messages");
        jdbc.execute("truncate table target_projections");
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

    @Test
    void targetLifecycleIsIdempotentVersionedAndRejectsMissingTargets() {
        TargetProjectionStore store = new TargetProjectionStore(targets);
        assertThrows(IllegalArgumentException.class, () -> store.requireActive("post", "42"));
        assertTrue(store.apply("post", "42", "alice", 1, true, Instant.now()));
        assertFalse(store.apply("post", "42", "alice", 1, true, Instant.now()));
        assertEquals("alice", store.requireActive("post", "42").getOwnerUsername());
        assertEquals("alice", store.requireActiveOwnedBy("post", "42", "alice").getOwnerUsername());
        assertThrows(IllegalArgumentException.class,
                () -> store.requireActiveOwnedBy("post", "42", "mallory"));
        assertTrue(store.apply("post", "42", null, 2, false, Instant.now()));
        assertThrows(IllegalArgumentException.class, () -> store.requireActive("post", "42"));
        assertFalse(store.apply("post", "42", "alice", 1, true, Instant.now()));
        assertThrows(IllegalArgumentException.class,
                () -> store.apply("unknown", "42", "alice", 1, true, Instant.now()));
    }

    @Test
    void targetReconciliationRepairsMissingAndStaleRows() {
        TargetProjectionStore store = new TargetProjectionStore(targets);
        store.apply("post", "stale", "old-owner", 1, true, Instant.now());
        store.apply("post", "orphan", "alice", 1, true, Instant.now());

        var result = store.reconcilePosts(java.util.List.of(
                new TargetProjectionStore.AuthoritativeTarget(
                        "stale", "new-owner", 3, true, Instant.now()),
                new TargetProjectionStore.AuthoritativeTarget(
                        "missing", "bob", 1, true, Instant.now())));

        assertEquals(2, result.applied());
        assertEquals(1, result.tombstoned());
        assertEquals("new-owner", store.requireActive("post", "stale").getOwnerUsername());
        assertEquals("bob", store.requireActive("post", "missing").getOwnerUsername());
        assertThrows(IllegalArgumentException.class, () -> store.requireActive("post", "orphan"));
    }

    @Test
    void auditedIdentityBackfillReportsUpdatedAndUnresolvedRows() throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS identity_test(username VARCHAR(100), user_id VARCHAR(36))");
        jdbc.execute("TRUNCATE TABLE identity_test");
        jdbc.update("INSERT INTO identity_test(username) VALUES ('alice')");
        HttpServer server=HttpServer.create(new InetSocketAddress("localhost",0),0);
        server.createContext("/internal/users/export",exchange->{
            byte[] body=("{\"items\":[{\"rowId\":1,\"userId\":\""+UUID.randomUUID()+
                    "\",\"username\":\"alice\",\"active\":true}],\"checkpoint\":1,\"hasMore\":false}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json"); exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        }); server.start();
        try {
            UserIdentityBackfill backfill=new UserIdentityBackfill("http://localhost:"+server.getAddress().getPort(),
                    "token",new ObjectMapper(),jdbc,new TransactionTemplate(transactionManager),java.util.List.of(
                    new UserIdentityBackfill.Target("UPDATE identity_test SET user_id=? WHERE username=? AND user_id IS NULL",
                            "SELECT COUNT(*) FROM identity_test WHERE user_id IS NULL")));
            var report=backfill.run();
            assertEquals(1,report.exportedUsers()); assertEquals(1,report.updatedRows()); assertEquals(0,report.unresolvedRows());
        } finally { server.stop(0); }
    }
}
