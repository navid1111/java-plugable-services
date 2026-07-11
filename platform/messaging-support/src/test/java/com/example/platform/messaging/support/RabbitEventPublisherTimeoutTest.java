package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitEventPublisherTimeoutTest {

    @Test
    void missingPublisherConfirmTimesOutAndLeavesOutboxForRetry() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        doNothing().when(template).send(
                anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        RabbitEventPublisher publisher = new RabbitEventPublisher(
                template, "platform.events.v1", Duration.ofMillis(1));
        OutboxMessage message = new OutboxMessage(UUID.randomUUID(), "post", "42",
                "post.created.v1", 1, "{}", Instant.now());

        assertThrows(EventPublishException.class, () -> publisher.publish(message));
    }
}
