package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TraceContextTest {

    @Test
    void newRootHasValidTraceparentAndCorrelation() {
        TraceContext root = TraceContext.newRoot();
        assertTrue(root.traceparent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"),
                "W3C traceparent: " + root.traceparent());
        assertNotNull(root.correlationId());
    }

    @Test
    void roundTripsThroughAHeaderCarrier() {
        TraceContext original = new TraceContext(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "corr-1", "cause-1", "event-1", "post", "42", "user-1");

        Map<String, String> carrier = new HashMap<>();
        original.writeTo(carrier::put);
        TraceContext restored = TraceContext.readFrom(carrier::get);

        assertEquals(original, restored, "every field survives the carrier round trip");
    }

    @Test
    void childSpanStaysInTraceAndLinksCausation() {
        TraceContext parent = new TraceContext(
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01",
                "corr-1", null, "event-1", "post", "42", "user-1");

        TraceContext child = parent.childSpan();

        assertEquals(parent.traceId(), child.traceId(), "same trace joins the spans");
        assertNotEquals(parent.spanId(), child.spanId(), "child is a new span");
        assertEquals("event-1", child.causationId(), "child is caused by the parent event");
        assertEquals(parent.correlationId(), child.correlationId());
    }

    @Test
    void missingTraceparentStartsANewRoot() {
        TraceContext restored = TraceContext.readFrom(key -> null);
        assertTrue(restored.traceparent().matches("00-[0-9a-f]{32}-[0-9a-f]{16}-01"));
        assertNotNull(restored.correlationId());
    }
}
