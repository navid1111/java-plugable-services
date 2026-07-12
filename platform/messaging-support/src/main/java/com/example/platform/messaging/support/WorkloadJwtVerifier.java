package com.example.platform.messaging.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Verifies workload signature, caller identity, audience, scope, expiry, and maximum TTL. */
public final class WorkloadJwtVerifier {
    public record Claims(String issuer, String audience, Set<String> scopes, String tokenId,
            Instant issuedAt, Instant expiresAt) {}

    private final String audience;
    private final Map<String, byte[]> trustedCallers;
    private final Duration maxTtl;
    private final Clock clock;
    private final ObjectMapper mapper;

    public WorkloadJwtVerifier(String audience, Map<String, String> trustedCallers,
            Duration maxTtl, ObjectMapper mapper) {
        this(audience, trustedCallers, maxTtl, Clock.systemUTC(), mapper);
    }

    WorkloadJwtVerifier(String audience, Map<String, String> trustedCallers,
            Duration maxTtl, Clock clock, ObjectMapper mapper) {
        if (audience == null || audience.isBlank()) throw new IllegalArgumentException("audience is required");
        if (trustedCallers == null || trustedCallers.isEmpty()) {
            throw new IllegalArgumentException("trusted callers are required");
        }
        this.audience = audience;
        this.trustedCallers = trustedCallers.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> {
                    byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                    if (value.length < 32) throw new IllegalArgumentException("trusted key must be at least 32 bytes");
                    return value;
                }));
        this.maxTtl = maxTtl;
        this.clock = clock;
        this.mapper = mapper;
    }

    public Claims verify(String authorization, String requiredScope) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) fail("missing bearer token");
            String[] parts = authorization.substring(7).split("\\.", -1);
            if (parts.length != 3) fail("invalid token format");
            JsonNode header = decode(parts[0]);
            if (!"HS256".equals(header.path("alg").asText()) || !"JWT".equals(header.path("typ").asText())) {
                fail("unsupported token header");
            }
            JsonNode payload = decode(parts[1]);
            String issuer = required(payload, "iss");
            if (!issuer.equals(required(payload, "sub"))) fail("subject must equal workload issuer");
            byte[] key = trustedCallers.get(issuer);
            if (key == null) fail("untrusted workload issuer");
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            byte[] expected = WorkloadJwtIssuer.sign(parts[0] + "." + parts[1], key);
            if (!MessageDigest.isEqual(expected, actual)) fail("invalid workload signature");

            String tokenAudience = required(payload, "aud");
            if (!audience.equals(tokenAudience)) fail("wrong workload audience");
            Instant issuedAt = Instant.ofEpochSecond(payload.path("iat").asLong(Long.MIN_VALUE));
            Instant expiresAt = Instant.ofEpochSecond(payload.path("exp").asLong(Long.MIN_VALUE));
            Instant now = clock.instant();
            if (issuedAt.isAfter(now.plusSeconds(30))) fail("token issued in the future");
            if (!expiresAt.isAfter(now)) fail("workload token expired");
            if (expiresAt.isAfter(issuedAt.plus(maxTtl))) fail("workload token TTL exceeds policy");
            Set<String> scopes = Set.copyOf(Arrays.asList(required(payload, "scope").split(" +")));
            if (requiredScope == null || requiredScope.isBlank() || !scopes.contains(requiredScope)) {
                fail("required workload scope missing");
            }
            return new Claims(issuer, tokenAudience, scopes, required(payload, "jti"), issuedAt, expiresAt);
        } catch (WorkloadAuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new WorkloadAuthenticationException("invalid workload token", e);
        }
    }

    public static Map<String, String> parseTrustedCallers(String value) {
        if (value == null || value.isBlank()) return Map.of();
        return Arrays.stream(value.split(","))
                .map(String::trim).filter(entry -> !entry.isEmpty())
                .map(entry -> entry.split("=", 2))
                .peek(parts -> { if (parts.length != 2 || parts[0].isBlank()) fail("invalid trusted caller entry"); })
                .collect(java.util.stream.Collectors.toUnmodifiableMap(parts -> parts[0], parts -> parts[1]));
    }

    private JsonNode decode(String encoded) {
        return mapper.readTree(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) fail("missing claim: " + field);
        return value;
    }

    private static void fail(String message) { throw new WorkloadAuthenticationException(message); }
}
