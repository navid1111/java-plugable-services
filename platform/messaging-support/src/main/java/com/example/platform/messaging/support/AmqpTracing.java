package com.example.platform.messaging.support;

import org.springframework.amqp.core.MessageProperties;

/** Injects/extracts a {@link TraceContext} on AMQP message properties (header carrier). */
public final class AmqpTracing {

    private AmqpTracing() {
    }

    public static void inject(TraceContext context, MessageProperties properties) {
        context.writeTo(properties::setHeader);
    }

    public static TraceContext extract(MessageProperties properties) {
        return TraceContext.readFrom(key -> {
            Object value = properties.getHeader(key);
            return value == null ? null : value.toString();
        });
    }
}
