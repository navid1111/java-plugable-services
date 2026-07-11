package com.example.platform.messaging.support;

import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Runs a consumer's side effect exactly once per (consumer, eventId) even under
 * at-least-once delivery. The business action and the inbox marker are written in one
 * transaction, so either both commit or neither does. A redelivery is short-circuited by
 * the inbox lookup; a concurrent redelivery that races past the lookup is stopped by the
 * inbox primary key — its transaction rolls back, undoing the duplicated side effect — and
 * is reported as an already-processed no-op rather than an error.
 */
public class InboxIdempotency {

    private final InboxMessageRepository inbox;
    private final TransactionOperations transactionOperations;

    public InboxIdempotency(InboxMessageRepository inbox, TransactionOperations transactionOperations) {
        this.inbox = inbox;
        this.transactionOperations = transactionOperations;
    }

    /**
     * @return {@code true} if this call performed the effect, {@code false} if the event was
     *     already processed (duplicate) and the action was skipped/rolled back.
     */
    public boolean process(String consumer, UUID eventId, String eventType, Runnable action) {
        try {
            return Boolean.TRUE.equals(transactionOperations.execute(status -> {
                if (inbox.existsByConsumerAndEventId(consumer, eventId)) {
                    return false;
                }
                action.run();
                // Force the INSERT now so a duplicate key surfaces inside this transaction
                // and rolls back the action rather than committing a second effect.
                inbox.saveAndFlush(new InboxMessage(consumer, eventId, eventType, Instant.now()));
                return true;
            }));
        } catch (DataIntegrityViolationException concurrentDuplicate) {
            return false;
        }
    }
}
