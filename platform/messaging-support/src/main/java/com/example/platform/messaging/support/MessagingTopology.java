package com.example.platform.messaging.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;

/**
 * Builds the durable broker topology for one consumer: the shared events exchange, a
 * per-consumer quorum work queue, a bounded quorum retry queue (delay via TTL, then back
 * to the events exchange), and a quorum dead-letter queue. Everything is durable and
 * declared idempotently, so a broker that loses its definitions recreates the whole
 * topology on the next declaration pass.
 *
 * <p>Naming per consumer {@code c}: work queue {@code c}, retry queue {@code c.retry},
 * dead-letter queue {@code c.dlq}. Rejected messages dead-letter to the DLX and land in
 * {@code c.dlq}; the consumer republishes to {@code c.retry} for a delayed re-attempt.
 */
public final class MessagingTopology {

    public static final String EVENTS_EXCHANGE = "platform.events.v1";
    public static final String DEAD_LETTER_EXCHANGE = "platform.events.dlx";

    private MessagingTopology() {
    }

    public record ConsumerSpec(
            String consumer,
            List<String> routingKeys,
            Duration retryDelay,
            long retryMaxLength) {
    }

    /** Exchanges shared by every consumer. Declare once per broker. */
    public static Declarables exchanges() {
        return new Declarables(
                new TopicExchange(EVENTS_EXCHANGE, true, false),
                new TopicExchange(DEAD_LETTER_EXCHANGE, true, false));
    }

    /** Queues and bindings for a single consumer. */
    public static Declarables forConsumer(ConsumerSpec spec) {
        String work = spec.consumer();
        String retry = work + ".retry";
        String dlq = work + ".dlq";

        Queue workQueue = QueueBuilder.durable(work)
                .quorum()
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(work)
                .build();

        // Delayed-retry queue: hold for retryDelay, then dead-letter back to the events
        // exchange so the message is re-delivered to the work queue. Bounded to shed load.
        Queue retryQueue = QueueBuilder.durable(retry)
                .quorum()
                .ttl((int) spec.retryDelay().toMillis())
                .deadLetterExchange(EVENTS_EXCHANGE)
                .maxLength(spec.retryMaxLength())
                .overflow(QueueBuilder.Overflow.rejectPublish)
                .build();

        Queue deadLetterQueue = QueueBuilder.durable(dlq).quorum().build();

        List<Declarable> declarables = new ArrayList<>();
        declarables.add(workQueue);
        declarables.add(retryQueue);
        declarables.add(deadLetterQueue);

        TopicExchange events = new TopicExchange(EVENTS_EXCHANGE, true, false);
        TopicExchange dlx = new TopicExchange(DEAD_LETTER_EXCHANGE, true, false);
        for (String routingKey : spec.routingKeys()) {
            declarables.add(BindingBuilder.bind(workQueue).to(events).with(routingKey));
        }
        // The work queue's dead-lettered messages route to the DLQ by the work-queue name.
        Binding dlqBinding = BindingBuilder.bind(deadLetterQueue).to(dlx).with(work);
        declarables.add(dlqBinding);

        return new Declarables(declarables);
    }
}
