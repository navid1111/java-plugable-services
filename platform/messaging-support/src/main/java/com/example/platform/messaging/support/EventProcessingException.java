package com.example.platform.messaging.support;

/** Base type for failures raised while processing a consumed event. */
public abstract class EventProcessingException extends RuntimeException {
    protected EventProcessingException(String message) {
        super(message);
    }

    protected EventProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
