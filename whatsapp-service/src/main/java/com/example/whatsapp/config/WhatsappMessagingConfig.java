package com.example.whatsapp.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.platform.messaging.support.SafeEventSerializer;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the shared outbox entity/repository alongside whatsapp's own persistence so the
 * service can write chat events transactionally. Only entities and repositories are pulled in
 * from {@code messaging-support}; its components are not component-scanned here.
 */
@Configuration
@EntityScan(basePackages = {
        "com.example.whatsapp.model",
        "com.example.platform.messaging.support"})
@EnableJpaRepositories(basePackages = {
        "com.example.whatsapp.repository",
        "com.example.platform.messaging.support"})
public class WhatsappMessagingConfig {

    @Bean
    SafeEventSerializer safeEventSerializer(ObjectMapper objectMapper) {
        return new SafeEventSerializer(objectMapper);
    }
}
