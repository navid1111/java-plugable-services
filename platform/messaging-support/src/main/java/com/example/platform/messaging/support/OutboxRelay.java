package com.example.platform.messaging.support;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Drains the outbox: claims a batch of due rows under {@code FOR UPDATE SKIP LOCKED},
 * publishes each with confirmed-only completion, and reschedules failures with capped
 * exponential backoff plus jitter. Because the claim and the publish+mark share one
 * transaction, two relay instances draining concurrently never publish the same row,
 * and a broker outage simply leaves rows unpublished for a later pass to drain.
 */
public class OutboxRelay {

    private final OutboxMessageRepository repository;
    private final EventPublisher publisher;
    private final OutboxRelayProperties properties;
    private final TransactionOperations transactionOperations;

    public OutboxRelay(OutboxMessageRepository repository, EventPublisher publisher,
            OutboxRelayProperties properties, TransactionOperations transactionOperations) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.transactionOperations = transactionOperations;
    }

    /** Claim and publish one batch. Returns the number of rows confirmed published. */
    public int drainOnce() {
        return transactionOperations.execute(status -> drainBatch());
    }

    private int drainBatch() {
        Instant now = Instant.now();
        List<OutboxMessage> batch = repository.claimBatch(now, properties.getBatchSize());
        int published = 0;
        for (OutboxMessage message : batch) {
            try {
                publisher.publish(message);
                // Reached only after the broker confirmed: confirmed-only completion.
                message.setPublishedAt(Instant.now());
                message.setLastError(null);
                published++;
            } catch (RuntimeException ex) {
                message.setAttempts(message.getAttempts() + 1);
                message.setAvailableAt(now.plus(backoff(message.getAttempts())));
                message.setLastError(bounded(ex));
            }
        }
        return published;
    }

    /** Capped exponential backoff with full jitter over the last doubling step. */
    Duration backoff(int attempts) {
        long baseMs = properties.getBaseBackoff().toMillis();
        long maxMs = properties.getMaxBackoff().toMillis();
        int shift = Math.min(attempts - 1, 30);
        long exp = Math.min(maxMs, baseMs << shift);
        long jitter = ThreadLocalRandom.current().nextLong(baseMs + 1);
        return Duration.ofMillis(Math.min(maxMs, exp + jitter));
    }

    private static String bounded(RuntimeException ex) {
        String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        return msg.substring(0, Math.min(1000, msg.length()));
    }
}
