package com.example.platform.messaging.support;

/** What a consumer should do with a delivery after attempting to process it. */
public enum MessageOutcome {
    /** Processed successfully (or a harmless duplicate): remove from the queue. */
    ACK,
    /** Transiently failed: send to the delayed retry queue for another attempt. */
    RETRY,
    /** Permanently failed or retry budget exhausted: send to the dead-letter queue. */
    DEAD_LETTER
}
