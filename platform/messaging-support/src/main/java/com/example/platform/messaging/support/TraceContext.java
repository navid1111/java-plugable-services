package com.example.platform.messaging.support;

import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Carrier-agnostic trace and correlation context propagated across every hop: a W3C
 * {@code traceparent}, the correlation id (constant for a whole workflow), the causation id
 * (the event that triggered this work), and the event/aggregate/user identifiers. The same
 * context is written to HTTP headers or AMQP message properties via {@link #writeTo} and
 * rebuilt via {@link #readFrom}, so one trace joins producer, broker, and consumer.
 */
public record TraceContext(
        String traceparent,
        String correlationId,
        String causationId,
        String eventId,
        String aggregateType,
        String aggregateId,
        String userId) {

    private static final HexFormat HEX = HexFormat.of();

    /** A brand-new root context beginning a fresh trace and correlation. */
    public static TraceContext newRoot() {
        return new TraceContext(traceparent(randomHex(16), randomHex(8)),
                UUID.randomUUID().toString(), null, null, null, null, null);
    }

    /** The 32-hex trace id shared by every span in the trace. */
    public String traceId() {
        return traceparent.split("-")[1];
    }

    /** The 16-hex span id of the current hop. */
    public String spanId() {
        return traceparent.split("-")[2];
    }

    /**
     * A child span in the same trace for emitting downstream work: it keeps the trace id and
     * correlation, starts a new span id, and records this context's event as the causation.
     */
    public TraceContext childSpan() {
        return new TraceContext(
                traceparent(traceId(), randomHex(8)),
                correlationId,
                eventId != null ? eventId : causationId,
                eventId,
                aggregateType,
                aggregateId,
                userId);
    }

    /** Write every present field to a carrier (HTTP header setter, AMQP property setter, …). */
    public void writeTo(BiConsumer<String, String> sink) {
        put(sink, MessagingHeaders.TRACEPARENT, traceparent);
        put(sink, MessagingHeaders.CORRELATION_ID, correlationId);
        put(sink, MessagingHeaders.CAUSATION_ID, causationId);
        put(sink, MessagingHeaders.EVENT_ID, eventId);
        put(sink, MessagingHeaders.AGGREGATE_TYPE, aggregateType);
        put(sink, MessagingHeaders.AGGREGATE_ID, aggregateId);
        put(sink, MessagingHeaders.USER_ID, userId);
    }

    /** Rebuild a context from a carrier; a missing traceparent starts a new root trace. */
    public static TraceContext readFrom(Function<String, String> source) {
        String traceparent = source.apply(MessagingHeaders.TRACEPARENT);
        String correlationId = source.apply(MessagingHeaders.CORRELATION_ID);
        return new TraceContext(
                traceparent != null ? traceparent : traceparent(randomHex(16), randomHex(8)),
                correlationId != null ? correlationId : UUID.randomUUID().toString(),
                source.apply(MessagingHeaders.CAUSATION_ID),
                source.apply(MessagingHeaders.EVENT_ID),
                source.apply(MessagingHeaders.AGGREGATE_TYPE),
                source.apply(MessagingHeaders.AGGREGATE_ID),
                source.apply(MessagingHeaders.USER_ID));
    }

    private static void put(BiConsumer<String, String> sink, String key, String value) {
        if (value != null) {
            sink.accept(key, value);
        }
    }

    private static String traceparent(String traceId, String spanId) {
        return "00-" + traceId + "-" + spanId + "-01";
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(buf);
        return HEX.formatHex(buf);
    }
}
