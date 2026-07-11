package com.example.media.messaging;

import java.time.Duration;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import com.example.platform.messaging.support.*;

@Configuration
public class OutboxPublisherConfiguration {
    @Bean OutboxRelayProperties mediaOutboxProperties() { return new OutboxRelayProperties(); }
    @Bean EventPublisher mediaEventPublisher(RabbitTemplate template) {
        template.setMandatory(true);
        return new RabbitEventPublisher(template, MessagingTopology.EVENTS_EXCHANGE, Duration.ofSeconds(5));
    }
    @Bean OutboxRelay mediaOutboxRelay(OutboxMessageRepository repository, EventPublisher publisher,
            OutboxRelayProperties properties, TransactionOperations transactions) {
        return new OutboxRelay(repository, publisher, properties, transactions);
    }
    @Bean MediaRelaySchedule mediaRelaySchedule(OutboxRelay relay) { return new MediaRelaySchedule(relay); }
    public static class MediaRelaySchedule {
        private final OutboxRelay relay;
        MediaRelaySchedule(OutboxRelay relay) { this.relay = relay; }
        @Scheduled(fixedDelayString = "${platform.messaging.outbox.poll-delay:500}")
        public void drain() { relay.drainOnce(); }
    }
}
