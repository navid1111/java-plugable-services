package com.example.platform.messaging.support;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Transitional JWT identity extraction: stable UUID subject plus username display claim. */
public record JwtIdentity(String userId, String username, boolean legacyToken) {
    public static JwtIdentity parse(String authorization, ObjectMapper mapper) {
        if (authorization == null || !authorization.startsWith("Bearer "))
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        String[] chunks = authorization.substring(7).split("\\.");
        if (chunks.length < 2) throw new IllegalArgumentException("Invalid JWT format");
        try {
            JsonNode claims = mapper.readTree(new String(Base64.getUrlDecoder().decode(chunks[1]),
                    StandardCharsets.UTF_8));
            String subject = claims.path("sub").asText();
            if (subject.isBlank()) throw new IllegalArgumentException("Missing subject claim");
            String display = claims.path("username").asText();
            try {
                String stableId = UUID.fromString(subject).toString();
                if (display.isBlank()) throw new IllegalArgumentException("Missing username claim");
                return new JwtIdentity(stableId, display, false);
            } catch (IllegalArgumentException notUuid) {
                return new JwtIdentity(null, subject, true);
            }
        } catch (Exception e) { throw new IllegalArgumentException("Failed to parse JWT payload", e); }
    }
}
