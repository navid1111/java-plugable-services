package com.example.platform.messaging.support;

/**
 * A failure that will recur on every redelivery — e.g. a business-rule violation. Retrying
 * cannot help, so it is dead-lettered immediately.
 */
public class DeterministicProcessingException extends EventProcessingException {
    public DeterministicProcessingException(String message) {
        super(message);
    }

    public DeterministicProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
