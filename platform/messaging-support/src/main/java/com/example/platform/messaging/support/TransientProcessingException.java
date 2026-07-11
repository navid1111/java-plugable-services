package com.example.platform.messaging.support;

/**
 * A failure that may succeed if retried later — e.g. a database timeout or an unavailable
 * downstream. Classified for retry until the attempt budget is exhausted.
 */
public class TransientProcessingException extends EventProcessingException {
    public TransientProcessingException(String message) {
        super(message);
    }

    public TransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
