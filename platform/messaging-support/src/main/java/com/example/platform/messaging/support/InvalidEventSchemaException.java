package com.example.platform.messaging.support;

/**
 * The event failed schema validation (malformed or missing required fields) — a poison
 * message. Retrying cannot fix it, so it is dead-lettered for inspection.
 */
public class InvalidEventSchemaException extends EventProcessingException {
    public InvalidEventSchemaException(String message) {
        super(message);
    }

    public InvalidEventSchemaException(String message, Throwable cause) {
        super(message, cause);
    }
}
