package com.example.platform.messaging.support;

/**
 * Signals that an outbox row was not confirmed published — an unconfirmed/nacked publish,
 * an unroutable (returned) message, or a broker/timeout error. The relay treats this as a
 * retryable failure and never marks the row published, preserving confirmed-only completion.
 */
public class EventPublishException extends RuntimeException {
    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
