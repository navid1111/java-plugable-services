package com.example.platform.messaging.support;

/**
 * Thrown when an event about to be persisted/published contains a field the platform
 * forbids in transit (credentials, tokens, secrets). Fail closed: the fact is never
 * written to the outbox rather than risking a leak downstream.
 */
public class SensitiveDataException extends RuntimeException {
    public SensitiveDataException(String message) {
        super(message);
    }
}
