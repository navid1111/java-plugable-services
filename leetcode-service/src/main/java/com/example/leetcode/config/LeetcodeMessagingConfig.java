package com.example.leetcode.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.platform.messaging.support.InboxIdempotency;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.platform.messaging.support.EventErrorClassifier;
import com.example.platform.messaging.support.EventOutcomeRouter;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.OutboxRelay;
import com.example.platform.messaging.support.OutboxRelayProperties;
import com.example.platform.messaging.support.RabbitEventPublisher;
import com.example.platform.messaging.support.SafeEventSerializer;
import com.example.leetcode.messaging.MessagingConfig;
import java.time.Duration;

import tools.jackson.databind.ObjectMapper;

/**
 * Wires the shared messaging-support persistence (inbox/outbox) alongside leetcode's own
 * entities so the judge flow can deduplicate deliveries and serialize events with the shared
 * contracts. Only entities and repositories are pulled in from {@code messaging-support}.
 */
@Configuration
@EntityScan(basePackages = {
        "com.example.leetcode.model",
        "com.example.platform.messaging.support"})
@EnableJpaRepositories(basePackages = {
        "com.example.leetcode.repository",
        "com.example.platform.messaging.support"})
@EnableConfigurationProperties(OutboxRelayProperties.class)
public class LeetcodeMessagingConfig {

    @Bean
    SafeEventSerializer safeEventSerializer(ObjectMapper objectMapper) {
        return new SafeEventSerializer(objectMapper);
    }

    @Bean
    InboxIdempotency inboxIdempotency(InboxMessageRepository inbox,
            PlatformTransactionManager transactionManager) {
        return new InboxIdempotency(inbox, new TransactionTemplate(transactionManager));
    }

    @Bean
    RabbitEventPublisher leetcodeEventPublisher(RabbitTemplate template,
            @Value("${platform.messaging.publisher-confirm-timeout:5s}") Duration timeout) {
        template.setMandatory(true);
        return new RabbitEventPublisher(template, MessagingConfig.EXCHANGE, timeout);
    }

    @Bean
    OutboxRelay leetcodeOutboxRelay(OutboxMessageRepository outbox,
            RabbitEventPublisher publisher, OutboxRelayProperties properties,
            PlatformTransactionManager transactionManager) {
        return new OutboxRelay(outbox, publisher, properties,
                new TransactionTemplate(transactionManager));
    }

    @Bean EventOutcomeRouter leetcodeOutcomeRouter(RabbitTemplate template) {
        return new EventOutcomeRouter(template);
    }

    @Bean EventErrorClassifier leetcodeErrorClassifier(
            @Value("${leetcode.messaging.consumer-max-attempts:5}") int maxAttempts) {
        return new EventErrorClassifier(maxAttempts);
    }
}
