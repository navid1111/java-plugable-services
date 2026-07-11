package com.example.whatsapp.config;

import java.time.Duration;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.platform.messaging.support.EventPublisher;
import com.example.platform.messaging.support.MessagingTopology;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.OutboxRelay;
import com.example.platform.messaging.support.OutboxRelayProperties;
import com.example.platform.messaging.support.RabbitEventPublisher;

/**
 * Production wiring that drains chat's outbox to RabbitMQ with confirmed, mandatory,
 * persistent delivery. Scheduling is already enabled app-wide. Disabled with
 * {@code chat.messaging.relay-enabled=false} in component tests that assert outbox contents
 * without a broker. (The broker being down never blocks message persistence or delivery.)
 */
@Configuration
@ConditionalOnProperty(name = "chat.messaging.relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class WhatsappOutboxRelayConfig {

    @Bean
    Declarables chatEventExchanges() {
        return MessagingTopology.exchanges();
    }

    @Bean
    OutboxRelayProperties chatOutboxRelayProperties() {
        return new OutboxRelayProperties();
    }

    @Bean
    EventPublisher chatEventPublisherRabbit(RabbitTemplate rabbitTemplate) {
        return new RabbitEventPublisher(rabbitTemplate, MessagingTopology.EVENTS_EXCHANGE,
                Duration.ofSeconds(5));
    }

    @Bean
    OutboxRelay chatOutboxRelay(OutboxMessageRepository outbox, EventPublisher publisher,
            OutboxRelayProperties properties, PlatformTransactionManager transactionManager) {
        return new OutboxRelay(outbox, publisher, properties, new TransactionTemplate(transactionManager));
    }

    @Component
    @ConditionalOnProperty(name = "chat.messaging.relay-enabled", havingValue = "true",
            matchIfMissing = true)
    static class OutboxRelayScheduler {
        private final OutboxRelay relay;

        OutboxRelayScheduler(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${chat.messaging.outbox.delay-ms:1000}")
        void drain() {
            relay.drainOnce();
        }
    }
}
