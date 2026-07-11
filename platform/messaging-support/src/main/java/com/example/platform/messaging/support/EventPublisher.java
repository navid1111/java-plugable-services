package com.example.platform.messaging.support;

/**
 * Publishes one outbox row and returns normally only when the broker has confirmed it.
 * Implementations must use mandatory routing and persistent delivery, and must throw
 * {@link EventPublishException} on nack, return (unroutable), or timeout so the relay
 * can retry. The event type is the routing key.
 */
public interface EventPublisher {
    void publish(OutboxMessage message) throws EventPublishException;
}
