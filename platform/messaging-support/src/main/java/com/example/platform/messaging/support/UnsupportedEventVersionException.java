package com.example.platform.messaging.support;

/**
 * The event's schema version is one this consumer cannot handle. Permanent for this
 * deployment, so it is dead-lettered (a newer consumer or a schema rollout resolves it).
 */
public class UnsupportedEventVersionException extends EventProcessingException {
    public UnsupportedEventVersionException(String message) {
        super(message);
    }
}
