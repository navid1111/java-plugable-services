package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class EventErrorClassifierTest {

    private final EventErrorClassifier classifier = new EventErrorClassifier(3);

    @Test
    void permanentFailuresAreDeadLettered() {
        assertEquals(MessageOutcome.DEAD_LETTER,
                classifier.classify(new UnsupportedEventVersionException("v9"), 1));
        assertEquals(MessageOutcome.DEAD_LETTER,
                classifier.classify(new InvalidEventSchemaException("bad"), 1));
        assertEquals(MessageOutcome.DEAD_LETTER,
                classifier.classify(new DeterministicProcessingException("rule"), 1));
    }

    @Test
    void transientFailuresRetryUntilBudgetExhausted() {
        assertEquals(MessageOutcome.RETRY,
                classifier.classify(new TransientProcessingException("db down"), 1));
        assertEquals(MessageOutcome.RETRY,
                classifier.classify(new TransientProcessingException("db down"), 2));
        assertEquals(MessageOutcome.DEAD_LETTER,
                classifier.classify(new TransientProcessingException("db down"), 3));
    }

    @Test
    void unknownFailuresAreTreatedAsTransientThenDeadLettered() {
        assertEquals(MessageOutcome.RETRY,
                classifier.classify(new RuntimeException("surprise"), 1));
        assertEquals(MessageOutcome.DEAD_LETTER,
                classifier.classify(new RuntimeException("surprise"), 3));
    }
}
