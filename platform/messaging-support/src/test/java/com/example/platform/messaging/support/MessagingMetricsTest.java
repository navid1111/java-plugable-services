package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class MessagingMetricsTest {

    @Test
    void exposesEveryRequiredMeter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AtomicLong backlog = new AtomicLong(3);
        AtomicLong dlq = new AtomicLong(1);
        MessagingMetrics metrics = new MessagingMetrics(registry, "tweeter",
                () -> 12.0, backlog::get, () -> 2.5, dlq::get);

        metrics.recordPublishFailure();
        metrics.recordRetry();
        metrics.recordRetry();
        metrics.recordProcessing(Duration.ofMillis(40));

        assertEquals(12.0, registry.get("messaging.outbox.age.seconds")
                .tag("service", "tweeter").gauge().value());
        assertEquals(3.0, registry.get("messaging.outbox.backlog").gauge().value());
        assertEquals(2.5, registry.get("messaging.projection.lag.seconds").gauge().value());
        assertEquals(1.0, registry.get("messaging.dlq.count").gauge().value());
        assertEquals(1.0, registry.get("messaging.publish.failures").counter().count());
        assertEquals(2.0, registry.get("messaging.consumer.retries").counter().count());
        assertEquals(1L, registry.get("messaging.consumer.latency").timer().count());

        // Gauges read live state.
        backlog.set(7);
        assertEquals(7.0, registry.get("messaging.outbox.backlog").gauge().value());
    }
}
