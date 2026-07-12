package com.example.tweeter.config;

import com.example.platform.messaging.support.WorkloadJwtIssuer;
import com.example.platform.messaging.support.WorkloadJwtVerifier;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
public class WorkloadSecurityConfiguration {
    @Bean WorkloadJwtIssuer workloadJwtIssuer(ObjectMapper mapper,
            @Value("${workload.jwt.issuer}") String issuer,
            @Value("${workload.jwt.secret}") String secret) {
        return new WorkloadJwtIssuer(issuer, secret, Duration.ofSeconds(60), mapper);
    }
    @Bean WorkloadJwtVerifier workloadJwtVerifier(ObjectMapper mapper,
            @Value("${workload.jwt.trusted-callers}") String trustedCallers) {
        return new WorkloadJwtVerifier("tweeter-service",
                WorkloadJwtVerifier.parseTrustedCallers(trustedCallers), Duration.ofMinutes(2), mapper);
    }
}
