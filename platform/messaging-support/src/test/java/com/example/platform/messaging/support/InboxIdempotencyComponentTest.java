package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves T006: at-least-once delivery yields exactly one effective database change. The
 * side effect is a row insert; the test drives both a sequential redelivery (caught by the
 * inbox lookup) and a concurrent redelivery (caught by the inbox primary key) and asserts a
 * single committed effect in each case.
 */
@SpringBootTest
@Testcontainers
class InboxIdempotencyComponentTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired InboxMessageRepository inbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private InboxIdempotency idempotency;

    @BeforeEach
    void setUp() {
        jdbc.execute("create table if not exists inbox_effects (id serial primary key, event uuid)");
        jdbc.execute("truncate table inbox_effects");
        jdbc.execute("truncate table inbox_messages");
        idempotency = new InboxIdempotency(inbox, new TransactionTemplate(txManager));
    }

    private long effectsFor(UUID event) {
        return jdbc.queryForObject(
                "select count(*) from inbox_effects where event = ?", Long.class, event);
    }

    @Test
    void sequentialRedeliveryAppliesEffectOnce() {
        String consumer = "post-search";
        UUID eventId = UUID.randomUUID();
        Runnable effect = () -> jdbc.update("insert into inbox_effects(event) values (?)", eventId);

        assertTrue(idempotency.process(consumer, eventId, "post.created.v1", effect),
                "first delivery performs the effect");
        assertFalse(idempotency.process(consumer, eventId, "post.created.v1", effect),
                "redelivery is a no-op");

        assertEquals(1, effectsFor(eventId), "effect committed exactly once");
        assertTrue(inbox.existsByConsumerAndEventId(consumer, eventId));
    }

    @Test
    void concurrentRedeliveryAppliesEffectOnce() throws Exception {
        String consumer = "post-search";
        UUID eventId = UUID.randomUUID();
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger performed = new AtomicInteger();

        Runnable worker = () -> {
            Runnable effect = () -> {
                jdbc.update("insert into inbox_effects(event) values (?)", eventId);
                sleepQuietly(); // hold the transaction open to force the PK race
            };
            try {
                barrier.await();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            if (idempotency.process(consumer, eventId, "post.created.v1", effect)) {
                performed.incrementAndGet();
            }
        };

        Thread a = new Thread(worker);
        Thread b = new Thread(worker);
        a.start();
        b.start();
        a.join();
        b.join();

        assertEquals(1, performed.get(), "exactly one delivery performed the effect");
        assertEquals(1, effectsFor(eventId), "effect committed exactly once despite the race");
        assertTrue(inbox.existsByConsumerAndEventId(consumer, eventId));
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
