package com.example.comment.config;

import com.example.platform.messaging.support.WorkloadJwtIssuer;
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
}
