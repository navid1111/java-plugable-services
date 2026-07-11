package com.example.platform.messaging.support;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Applies a {@link MessageOutcome} to the T007 topology by republishing the delivery:
 * RETRY sends it to the consumer's delayed retry queue (TTL then back to the events
 * exchange), DEAD_LETTER sends it to the dead-letter exchange (landing in the DLQ), and ACK
 * does nothing. The consumer acks the original delivery after this returns, so a message is
 * always safely re-homed before it leaves the work queue.
 */
public class EventOutcomeRouter {

    private final RabbitTemplate template;

    public EventOutcomeRouter(RabbitTemplate template) {
        this.template = template;
    }

    public void route(String consumer, MessageOutcome outcome, Message delivery) {
        switch (outcome) {
            case ACK -> {
                // nothing to re-home
            }
            // Default exchange routes by queue name straight to the retry queue.
            case RETRY -> template.send("", consumer + ".retry", delivery);
            case DEAD_LETTER ->
                    template.send(MessagingTopology.DEAD_LETTER_EXCHANGE, consumer, delivery);
        }
    }
}
