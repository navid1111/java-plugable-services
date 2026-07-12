package com.example.demo.config;

import com.example.platform.messaging.support.WorkloadJwtVerifier;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class WorkloadSecurityConfiguration {
    @Bean
    WorkloadJwtVerifier workloadJwtVerifier(ObjectMapper mapper,
            @Value("${workload.jwt.trusted-callers}") String trustedCallers) {
        return new WorkloadJwtVerifier("auth-service",
                WorkloadJwtVerifier.parseTrustedCallers(trustedCallers), Duration.ofMinutes(2), mapper);
    }
}
