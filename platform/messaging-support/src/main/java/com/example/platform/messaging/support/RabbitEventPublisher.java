package com.example.platform.messaging.support;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes outbox rows to a durable topic exchange with the event type as routing key,
 * persistent delivery, and mandatory routing, and blocks for the publisher confirm.
 * Returns normally only when the broker acked and did not return the message as
 * unroutable — otherwise throws {@link EventPublishException} so the relay retries.
 *
 * <p>The template must be configured with correlated publisher confirms and returns
 * (Spring Boot: {@code spring.rabbitmq.publisher-confirm-type=correlated},
 * {@code spring.rabbitmq.publisher-returns=true}).
 */
public class RabbitEventPublisher implements EventPublisher {

    private final RabbitTemplate template;
    private final String exchange;
    private final Duration confirmTimeout;

    public RabbitEventPublisher(RabbitTemplate template, String exchange, Duration confirmTimeout) {
        this.template = template;
        this.exchange = exchange;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public void publish(OutboxMessage message) {
        String id = message.getId().toString();
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        props.setMessageId(id);
        props.setType(message.getEventType());
        props.setHeader("aggregateType", message.getAggregateType());
        props.setHeader("aggregateId", message.getAggregateId());
        Message amqp = new Message(message.getPayload().getBytes(StandardCharsets.UTF_8), props);

        CorrelationData correlation = new CorrelationData(id);
        template.send(exchange, message.getEventType(), amqp, correlation);

        try {
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(confirmTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (confirm == null || !confirm.isAck()) {
                throw new EventPublishException("publish nacked for " + id
                        + (confirm != null ? ": " + confirm.getReason() : ""));
            }
            if (correlation.getReturned() != null) {
                throw new EventPublishException("message " + id + " unroutable to "
                        + exchange + "/" + message.getEventType());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("interrupted awaiting confirm for " + id, e);
        } catch (ExecutionException | TimeoutException e) {
            throw new EventPublishException("confirm failed for " + id, e);
        }
    }
}
