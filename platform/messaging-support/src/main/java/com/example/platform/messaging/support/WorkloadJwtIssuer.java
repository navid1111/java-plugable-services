package com.example.platform.messaging.support;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.ObjectMapper;

/** Issues short-lived HS256 tokens for one workload identity. */
public final class WorkloadJwtIssuer {
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private final String issuer;
    private final byte[] key;
    private final Duration ttl;
    private final Clock clock;
    private final ObjectMapper mapper;

    public WorkloadJwtIssuer(String issuer, String secret, Duration ttl, ObjectMapper mapper) {
        this(issuer, secret, ttl, Clock.systemUTC(), mapper);
    }

    WorkloadJwtIssuer(String issuer, String secret, Duration ttl, Clock clock, ObjectMapper mapper) {
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("issuer is required");
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("workload JWT secret must be at least 32 bytes");
        }
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("workload JWT TTL must be between 1ns and 5m");
        }
        this.issuer = issuer;
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
        this.clock = clock;
        this.mapper = mapper;
    }

    public String issue(String audience, Set<String> scopes) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("audience is required");
        if (scopes == null || scopes.isEmpty() || scopes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("at least one scope is required");
        }
        Instant now = clock.instant();
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode(mapper.writeValueAsString(java.util.Map.of(
                "iss", issuer, "sub", issuer, "aud", audience,
                "scope", String.join(" ", new java.util.TreeSet<>(scopes)),
                "iat", now.getEpochSecond(), "exp", now.plus(ttl).getEpochSecond(),
                "jti", UUID.randomUUID().toString())));
        String signingInput = header + "." + payload;
        return signingInput + "." + B64.encodeToString(sign(signingInput, key));
    }

    public String authorization(String audience, String scope) {
        return "Bearer " + issue(audience, Set.of(scope));
    }

    static byte[] sign(String input, byte[] key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String encode(String value) {
        return B64.encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
