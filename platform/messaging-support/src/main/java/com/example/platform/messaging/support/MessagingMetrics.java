package com.example.platform.messaging.support;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Standard messaging telemetry for a service, tagged with the service name. Gauges track
 * live state (oldest unpublished outbox row's age, outbox backlog, projection lag, DLQ
 * depth) via caller-supplied readers; counters and a timer track events (publish failures,
 * consumer retries, processing latency). All meter names are shared across services so one
 * dashboard fits every producer/consumer.
 */
public class MessagingMetrics {

    private final Counter publishFailures;
    private final Counter retries;
    private final Timer processingLatency;

    public MessagingMetrics(MeterRegistry registry, String service,
            Supplier<Number> outboxAgeSeconds,
            Supplier<Number> outboxBacklog,
            Supplier<Number> projectionLagSeconds,
            Supplier<Number> dlqDepth) {
        Tags tags = Tags.of("service", service);
        Gauge.builder("messaging.outbox.age.seconds", outboxAgeSeconds)
                .description("Age of the oldest unpublished outbox row").tags(tags).register(registry);
        Gauge.builder("messaging.outbox.backlog", outboxBacklog)
                .description("Unpublished outbox rows").tags(tags).register(registry);
        Gauge.builder("messaging.projection.lag.seconds", projectionLagSeconds)
                .description("Lag between event time and projection apply time").tags(tags).register(registry);
        Gauge.builder("messaging.dlq.count", dlqDepth)
                .description("Messages parked in the dead-letter queue").tags(tags).register(registry);
        this.publishFailures = Counter.builder("messaging.publish.failures")
                .description("Outbox publish attempts that were not confirmed").tags(tags).register(registry);
        this.retries = Counter.builder("messaging.consumer.retries")
                .description("Consumer deliveries routed to the retry queue").tags(tags).register(registry);
        this.processingLatency = Timer.builder("messaging.consumer.latency")
                .description("Time to process one consumed event").tags(tags).register(registry);
    }

    public void recordPublishFailure() {
        publishFailures.increment();
    }

    public void recordRetry() {
        retries.increment();
    }

    public void recordProcessing(Duration elapsed) {
        processingLatency.record(elapsed);
    }
}
