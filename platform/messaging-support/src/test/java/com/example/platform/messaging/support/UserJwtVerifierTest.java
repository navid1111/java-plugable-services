package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class UserJwtVerifierTest {
    private static final String SECRET = "test-user-jwt-secret-that-is-at-least-32-bytes";
    private static final Instant NOW = Instant.parse("2026-07-12T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper();
    private final UserJwtVerifier verifier = new UserJwtVerifier(
            SECRET, "springboot-auth", Clock.fixed(NOW, ZoneOffset.UTC), mapper);

    @Test
    void verifiesSignatureIssuerExpiryRoleScopeAndIdentity() {
        String token = token("springboot-auth", NOW.plusSeconds(60).getEpochSecond(),
                "[\"USER\"]", "user");
        var identity = verifier.verifyUser("Bearer " + token);
        assertEquals("alice", identity.username());
        assertEquals("550e8400-e29b-41d4-a716-446655440000", identity.userId());
    }

    @Test
    void forgedExpiredAndWrongIssuerTokensFail() {
        String valid = token("springboot-auth", NOW.plusSeconds(60).getEpochSecond(),
                "[\"USER\"]", "user");
        String[] parts = valid.split("\\.");
        byte[] badSignature = Base64.getUrlDecoder().decode(parts[2]);
        badSignature[0] ^= 1;
        String forged = parts[0] + "." + parts[1] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(badSignature);
        assertThrows(UserAuthenticationException.class,
                () -> verifier.verifyUser("Bearer " + forged));
        assertThrows(UserAuthenticationException.class,
                () -> verifier.verifyUser("Bearer " + token("springboot-auth",
                        NOW.minusSeconds(1).getEpochSecond(), "[\"USER\"]", "user")));
        assertThrows(UserAuthenticationException.class,
                () -> verifier.verifyUser("Bearer " + token("attacker",
                        NOW.plusSeconds(60).getEpochSecond(), "[\"USER\"]", "user")));
    }

    @Test
    void missingUserAuthorityAndAdminEscalationFail() {
        assertThrows(UserAuthenticationException.class,
                () -> verifier.verifyUser("Bearer " + token("springboot-auth",
                        NOW.plusSeconds(60).getEpochSecond(), "[\"ADMIN\"]", "admin")));
        var principal = verifier.verify("Bearer " + token("springboot-auth",
                NOW.plusSeconds(60).getEpochSecond(), "[\"USER\"]", "user"));
        assertThrows(UserAuthenticationException.class, () -> principal.requireRole("ADMIN"));
    }

    @Test
    void rejectsSignedUsernameSubjectFromCompatibilityPeriod() {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"iss\":\"springboot-auth\",\"sub\":\"alice\""
                + ",\"username\":\"alice\",\"exp\":" + NOW.plusSeconds(60).getEpochSecond()
                + ",\"roles\":[\"USER\"],\"scope\":\"user\"}");
        String input = header + "." + payload;
        String token = input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                WorkloadJwtIssuer.sign(input, SECRET.getBytes(StandardCharsets.UTF_8)));
        assertThrows(UserAuthenticationException.class, () -> verifier.verifyUser("Bearer " + token));
    }

    private String token(String issuer, long exp, String rolesJson, String scope) {
        String header = encode("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = encode("{\"iss\":\"" + issuer
                + "\",\"sub\":\"550e8400-e29b-41d4-a716-446655440000\""
                + ",\"username\":\"alice\",\"exp\":" + exp
                + ",\"roles\":" + rolesJson + ",\"scope\":\"" + scope + "\"}");
        String input = header + "." + payload;
        return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                WorkloadJwtIssuer.sign(input, SECRET.getBytes(StandardCharsets.UTF_8)));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                value.getBytes(StandardCharsets.UTF_8));
    }
}
