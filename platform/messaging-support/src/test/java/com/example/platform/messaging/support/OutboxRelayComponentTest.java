package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proves T005's two guarantees against real PostgreSQL, isolating the broker with a
 * controllable {@link EventPublisher}: two relay instances draining concurrently never
 * publish the same claimed row (FOR UPDATE SKIP LOCKED), and a transient publish outage
 * leaves the backlog intact and fully drains once publishing recovers.
 */
@SpringBootTest
@Testcontainers
class OutboxRelayComponentTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OutboxMessageRepository outbox;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    private TransactionOperations tx;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table outbox_messages");
        tx = new TransactionTemplate(txManager);
    }

    private void seed(int count) {
        Instant now = Instant.now().minusSeconds(1);
        for (int i = 0; i < count; i++) {
            outbox.save(new OutboxMessage(UUID.randomUUID(), "post", "p" + i,
                    "post.created.v1", 1, "{\"i\":" + i + "}", now));
        }
    }

    @Test
    void twoConcurrentRelaysNeverPublishTheSameRowTwice() throws Exception {
        int total = 120;
        seed(total);

        // Both relays share one recorder; a second publish of any id would be visible here.
        ConcurrentHashMap<UUID, Integer> publishCounts = new ConcurrentHashMap<>();
        EventPublisher recorder = message -> {
            publishCounts.merge(message.getId(), 1, Integer::sum);
            sleepQuietly(); // widen the window for the two claimers to contend
        };
        var props = new OutboxRelayProperties();
        props.setBatchSize(3); // small batches force interleaving
        OutboxRelay relayA = new OutboxRelay(outbox, recorder, props, tx);
        OutboxRelay relayB = new OutboxRelay(outbox, recorder, props, tx);

        Thread t1 = new Thread(() -> { while (outbox.countByPublishedAtIsNull() > 0) relayA.drainOnce(); });
        Thread t2 = new Thread(() -> { while (outbox.countByPublishedAtIsNull() > 0) relayB.drainOnce(); });
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertEquals(0, outbox.countByPublishedAtIsNull(), "all rows should be published");
        assertEquals(total, publishCounts.size(), "every row published exactly once");
        assertTrue(publishCounts.values().stream().allMatch(c -> c == 1),
                "no row was published twice: " + publishCounts);
    }

    @Test
    void backlogSurvivesOutageAndFullyDrainsOnRecovery() throws Exception {
        int total = 30;
        seed(total);

        AtomicBoolean brokerDown = new AtomicBoolean(true);
        ConcurrentHashMap<UUID, Integer> delivered = new ConcurrentHashMap<>();
        EventPublisher flaky = message -> {
            if (brokerDown.get()) {
                throw new EventPublishException("broker down");
            }
            delivered.merge(message.getId(), 1, Integer::sum);
        };
        var props = new OutboxRelayProperties();
        props.setBaseBackoff(java.time.Duration.ofMillis(1));
        props.setMaxBackoff(java.time.Duration.ofMillis(10));
        OutboxRelay relay = new OutboxRelay(outbox, flaky, props, tx);

        // Outage: nothing is published, but nothing is lost either.
        int published = relay.drainOnce();
        assertEquals(0, published);
        assertEquals(total, outbox.countByPublishedAtIsNull(), "backlog intact during outage");
        assertTrue(outbox.findAll().stream().allMatch(m -> m.getAttempts() >= 1),
                "each failed row records an attempt");
        assertTrue(outbox.findAll().stream().allMatch(m -> m.getLastError() != null),
                "each failed row records the error");

        // Recovery: wait out the short backoff, then drain to empty.
        brokerDown.set(false);
        Thread.sleep(40);
        for (int i = 0; i < 100 && outbox.countByPublishedAtIsNull() > 0; i++) {
            relay.drainOnce();
        }
        assertEquals(0, outbox.countByPublishedAtIsNull(), "backlog fully drained on recovery");
        assertEquals(total, delivered.size());
        assertTrue(delivered.values().stream().allMatch(c -> c == 1), "no duplicate effective delivery");
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
