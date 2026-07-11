package com.example.platform.messaging.target;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

class TargetTypeRegistryTest {
    @Test void postIsOwnedByTweeter() {
        assertEquals("tweeter-service", TargetTypeRegistry.requireOwner("post"));
    }
    @Test void unknownTargetsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TargetTypeRegistry.requireOwner("video"));
    }
}
