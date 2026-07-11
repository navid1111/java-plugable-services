package com.example.booking.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.platform.messaging.support.SafeEventSerializer;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the shared outbox entity/repository alongside booking's own persistence so the
 * service can write domain events transactionally. Only entities and repositories are pulled
 * in from {@code messaging-support}; its components are not component-scanned here.
 */
@Configuration
@EntityScan(basePackages = {
        "com.example.booking.model",
        "com.example.platform.messaging.support"})
@EnableJpaRepositories(basePackages = {
        "com.example.booking.repository",
        "com.example.platform.messaging.support"})
public class BookingMessagingConfig {

    @Bean
    SafeEventSerializer safeEventSerializer(ObjectMapper objectMapper) {
        return new SafeEventSerializer(objectMapper);
    }
}
