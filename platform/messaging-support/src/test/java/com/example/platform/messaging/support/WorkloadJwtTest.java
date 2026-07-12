package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkloadJwtTest {
    private static final String SECRET = "test-workload-secret-that-is-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void verifiesCallerAudienceScopeAndShortLifetime() {
        WorkloadJwtIssuer issuer = new WorkloadJwtIssuer("post-search-service", SECRET,
                Duration.ofSeconds(60), clock, mapper);
        WorkloadJwtVerifier verifier = new WorkloadJwtVerifier("tweeter-service",
                Map.of("post-search-service", SECRET), Duration.ofMinutes(2), clock, mapper);

        var claims = verifier.verify("Bearer " + issuer.issue("tweeter-service",
                Set.of("posts:export")), "posts:export");

        assertEquals("post-search-service", claims.issuer());
        assertEquals("tweeter-service", claims.audience());
    }

    @Test
    void forgedWrongAudienceAndMissingScopeTokensFail() {
        WorkloadJwtIssuer issuer = new WorkloadJwtIssuer("post-search-service", SECRET,
                Duration.ofSeconds(60), clock, mapper);
        WorkloadJwtVerifier verifier = new WorkloadJwtVerifier("tweeter-service",
                Map.of("post-search-service", SECRET), Duration.ofMinutes(2), clock, mapper);
        String valid = issuer.issue("tweeter-service", Set.of("posts:export"));
        String forged = corruptSignature(valid);

        assertThrows(WorkloadAuthenticationException.class,
                () -> verifier.verify("Bearer " + forged, "posts:export"));
        assertThrows(WorkloadAuthenticationException.class,
                () -> verifier.verify("Bearer " + issuer.issue("auth-service", Set.of("posts:export")),
                        "posts:export"));
        assertThrows(WorkloadAuthenticationException.class,
                () -> verifier.verify("Bearer " + valid, "posts:rebuild"));
        assertThrows(WorkloadAuthenticationException.class,
                () -> verifier.verify(null, "posts:export"));
    }

    @Test
    void expiredAndExcessiveLifetimeTokensFail() {
        WorkloadJwtIssuer expiredIssuer = new WorkloadJwtIssuer("post-search-service", SECRET,
                Duration.ofSeconds(30), Clock.fixed(NOW.minusSeconds(60), ZoneOffset.UTC), mapper);
        WorkloadJwtIssuer longIssuer = new WorkloadJwtIssuer("post-search-service", SECRET,
                Duration.ofMinutes(3), clock, mapper);
        WorkloadJwtVerifier verifier = new WorkloadJwtVerifier("tweeter-service",
                Map.of("post-search-service", SECRET), Duration.ofMinutes(2), clock, mapper);

        assertThrows(WorkloadAuthenticationException.class,
                () -> verifier.verify("Bearer " + expiredIssuer.issue("tweeter-service",
                        Set.of("posts:export")), "posts:export"));
        assertThrows(WorkloadAuthenticationException.class,
                () -> verifier.verify("Bearer " + longIssuer.issue("tweeter-service",
                        Set.of("posts:export")), "posts:export"));
    }

    private static String corruptSignature(String token) {
        String[] parts = token.split("\\.");
        byte[] signature = java.util.Base64.getUrlDecoder().decode(parts[2]);
        signature[0] ^= 1;
        return parts[0] + "." + parts[1] + "."
                + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
    }
}
