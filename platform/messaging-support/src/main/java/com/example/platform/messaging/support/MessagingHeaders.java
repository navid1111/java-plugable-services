package com.example.platform.messaging.support;

/** Header/property names used to carry trace and correlation context over HTTP and AMQP. */
public final class MessagingHeaders {

    /** W3C Trace Context header; shared verbatim by HTTP and AMQP. */
    public static final String TRACEPARENT = "traceparent";
    public static final String CORRELATION_ID = "x-correlation-id";
    public static final String CAUSATION_ID = "x-causation-id";
    public static final String EVENT_ID = "x-event-id";
    public static final String AGGREGATE_TYPE = "x-aggregate-type";
    public static final String AGGREGATE_ID = "x-aggregate-id";
    public static final String USER_ID = "x-user-id";

    private MessagingHeaders() {
    }
}
