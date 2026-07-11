package com.example.leetcode.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.platform.messaging.support.InboxIdempotency;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;

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
}
