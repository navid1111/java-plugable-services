package com.example.platform.messaging.support;

/**
 * Maps a processing failure to a {@link MessageOutcome}. Permanent failures (unsupported
 * version, invalid schema, deterministic) go straight to the dead-letter queue. Transient
 * failures — and unclassified errors, treated conservatively as possibly transient — are
 * retried until the attempt budget is exhausted, after which they too are dead-lettered so
 * a poison message can never loop forever.
 */
public class EventErrorClassifier {

    private final int maxAttempts;

    public EventErrorClassifier(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.maxAttempts = maxAttempts;
    }

    /**
     * @param error the failure raised while processing
     * @param attempts how many times delivery has now been attempted (this one included)
     */
    public MessageOutcome classify(Throwable error, int attempts) {
        if (error instanceof UnsupportedEventVersionException
                || error instanceof InvalidEventSchemaException
                || error instanceof DeterministicProcessingException) {
            return MessageOutcome.DEAD_LETTER;
        }
        // Transient or unknown: retry while budget remains, else dead-letter.
        return attempts < maxAttempts ? MessageOutcome.RETRY : MessageOutcome.DEAD_LETTER;
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
