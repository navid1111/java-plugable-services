package com.example.tweeter.messaging;

import java.time.Duration;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.example.platform.messaging.support.*;

@Configuration
@EnableScheduling
public class OutboxPublisherConfiguration {
    @Bean Declarables platformExchanges() { return MessagingTopology.exchanges(); }
    @Bean OutboxRelayProperties outboxRelayProperties() { return new OutboxRelayProperties(); }
    @Bean EventPublisher eventPublisher(RabbitTemplate template) {
        template.setMandatory(true);
        return new RabbitEventPublisher(template, MessagingTopology.EVENTS_EXCHANGE, Duration.ofSeconds(5));
    }
    @Bean OutboxRelay outboxRelay(OutboxMessageRepository repository, EventPublisher publisher,
            OutboxRelayProperties properties, TransactionOperations transactions) {
        return new OutboxRelay(repository, publisher, properties, transactions);
    }
    @Bean
    @ConditionalOnProperty(name = "platform.messaging.outbox.scheduling-enabled", havingValue = "true", matchIfMissing = true)
    RelaySchedule relaySchedule(OutboxRelay relay) { return new RelaySchedule(relay); }
    public static class RelaySchedule {
        private final OutboxRelay relay;
        RelaySchedule(OutboxRelay relay) { this.relay = relay; }
        @Scheduled(fixedDelayString = "${platform.messaging.outbox.poll-delay:500}")
        public void drain() { relay.drainOnce(); }
    }
}
