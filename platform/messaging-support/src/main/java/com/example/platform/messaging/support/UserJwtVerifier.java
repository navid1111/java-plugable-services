package com.example.platform.messaging.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Defense-in-depth verifier for end-user HS256 JWTs at every service boundary. */
public final class UserJwtVerifier {
    public record Principal(JwtIdentity identity, Set<String> roles, Set<String> scopes) {
        public void requireRole(String role) {
            if (!roles.contains(role)) throw new UserAuthenticationException("required role missing");
        }
        public void requireScope(String scope) {
            if (!scopes.contains(scope)) throw new UserAuthenticationException("required scope missing");
        }
    }

    private final byte[] key;
    private final String issuer;
    private final Clock clock;
    private final ObjectMapper mapper;

    public UserJwtVerifier(String secret, String issuer, ObjectMapper mapper) {
        this(secret, issuer, Clock.systemUTC(), mapper);
    }

    UserJwtVerifier(String secret, String issuer, Clock clock, ObjectMapper mapper) {
        this.key = secret.getBytes(StandardCharsets.UTF_8);
        if (key.length < 32) throw new IllegalArgumentException("user JWT secret must be at least 32 bytes");
        if (issuer == null || issuer.isBlank()) throw new IllegalArgumentException("user JWT issuer is required");
        this.issuer = issuer;
        this.clock = clock;
        this.mapper = mapper;
    }

    public Principal verify(String authorization) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) fail("missing bearer token");
            String[] parts = authorization.substring(7).split("\\.", -1);
            if (parts.length != 3) fail("invalid JWT format");
            JsonNode header = decode(parts[0]);
            if (!"HS256".equals(header.path("alg").asText())) fail("unsupported JWT algorithm");
            JsonNode claims = decode(parts[1]);
            if (!issuer.equals(required(claims, "iss"))) fail("wrong JWT issuer");
            byte[] expected = WorkloadJwtIssuer.sign(parts[0] + "." + parts[1], key);
            byte[] actual = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) fail("invalid JWT signature");
            long exp = claims.path("exp").asLong(Long.MIN_VALUE);
            if (exp == Long.MIN_VALUE || !Instant.ofEpochSecond(exp).isAfter(clock.instant())) {
                fail("JWT expired");
            }
            String subject = required(claims, "sub");
            String username = claims.path("username").asText();
            String userId;
            try { userId = UUID.fromString(subject).toString(); }
            catch (IllegalArgumentException invalidSubject) { fail("JWT subject must be a stable user UUID"); return null; }
            if (username.isBlank()) fail("missing claim: username");
            Set<String> roles = values(claims.get("roles"));
            Set<String> scopes = values(claims.get("scope"));
            return new Principal(new JwtIdentity(userId, username), roles, scopes);
        } catch (UserAuthenticationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new UserAuthenticationException("invalid user JWT", e);
        }
    }

    public JwtIdentity verifyUser(String authorization) {
        Principal principal = verify(authorization);
        principal.requireRole("USER");
        principal.requireScope("user");
        return principal.identity();
    }

    private Set<String> values(JsonNode value) {
        if (value == null || value.isNull()) return Set.of();
        Set<String> result = new HashSet<>();
        if (value.isArray()) value.forEach(item -> result.add(item.asText()));
        else result.addAll(Arrays.asList(value.asText().split(" +")));
        result.removeIf(String::isBlank);
        return Set.copyOf(result);
    }

    private JsonNode decode(String encoded) {
        return mapper.readTree(new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8));
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) fail("missing claim: " + field);
        return value;
    }

    private static void fail(String message) { throw new UserAuthenticationException(message); }
}
