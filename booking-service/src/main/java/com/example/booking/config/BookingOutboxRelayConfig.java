package com.example.booking.config;

import java.time.Duration;

import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
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
 * Production wiring that drains booking's outbox to RabbitMQ with confirmed, mandatory,
 * persistent delivery. Disabled with {@code booking.messaging.relay-enabled=false} (e.g. in
 * component tests that assert outbox contents without a broker).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "booking.messaging.relay-enabled", havingValue = "true",
        matchIfMissing = true)
public class BookingOutboxRelayConfig {

    /** Declares the shared events exchange so the producer can publish to it. */
    @Bean
    Declarables bookingEventExchanges() {
        return MessagingTopology.exchanges();
    }

    @Bean
    OutboxRelayProperties bookingOutboxRelayProperties() {
        return new OutboxRelayProperties();
    }

    @Bean
    EventPublisher bookingEventPublisherRabbit(RabbitTemplate rabbitTemplate) {
        return new RabbitEventPublisher(rabbitTemplate, MessagingTopology.EVENTS_EXCHANGE,
                Duration.ofSeconds(5));
    }

    @Bean
    OutboxRelay bookingOutboxRelay(OutboxMessageRepository outbox, EventPublisher publisher,
            OutboxRelayProperties properties, PlatformTransactionManager transactionManager) {
        return new OutboxRelay(outbox, publisher, properties, new TransactionTemplate(transactionManager));
    }

    @Component
    @ConditionalOnProperty(name = "booking.messaging.relay-enabled", havingValue = "true",
            matchIfMissing = true)
    static class OutboxRelayScheduler {
        private final OutboxRelay relay;

        OutboxRelayScheduler(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${booking.messaging.outbox.delay-ms:1000}")
        void drain() {
            relay.drainOnce();
        }
    }
}
