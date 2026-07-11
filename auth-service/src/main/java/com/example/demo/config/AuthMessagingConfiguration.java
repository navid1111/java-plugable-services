package com.example.demo.config;

import java.time.Duration;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionOperations;
import com.example.platform.messaging.support.*;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class AuthMessagingConfiguration {
    @Bean Declarables authEventExchange() { return MessagingTopology.exchanges(); }
    @Bean TransactionalEventWriter authEventWriter(OutboxMessageRepository repository, ObjectMapper mapper) {
        return new TransactionalEventWriter(repository, mapper);
    }
    @Bean OutboxRelayProperties authOutboxProperties() { return new OutboxRelayProperties(); }
    @Bean EventPublisher authEventPublisher(RabbitTemplate template) {
        template.setMandatory(true);
        return new RabbitEventPublisher(template, MessagingTopology.EVENTS_EXCHANGE, Duration.ofSeconds(5));
    }
    @Bean OutboxRelay authOutboxRelay(OutboxMessageRepository repository, EventPublisher publisher,
            OutboxRelayProperties properties, TransactionOperations transactions) {
        return new OutboxRelay(repository, publisher, properties, transactions);
    }
    @Bean RelaySchedule authRelaySchedule(OutboxRelay relay) { return new RelaySchedule(relay); }
    public static class RelaySchedule {
        private final OutboxRelay relay;
        RelaySchedule(OutboxRelay relay) { this.relay = relay; }
        @Scheduled(fixedDelayString = "${platform.messaging.outbox.poll-delay:500}") public void drain() { relay.drainOnce(); }
    }
}
