package com.example.booking.security;

import java.util.Base64;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class JwtHelper {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String extractUsername(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        String[] chunks = token.split("\\.");
        if (chunks.length < 2) {
            throw new IllegalArgumentException("invalid JWT format");
        }

        try {
            String payload = new String(Base64.getUrlDecoder().decode(chunks[1]));
            JsonNode jsonNode = objectMapper.readTree(payload);
            JsonNode subject = jsonNode.get("sub");
            if (subject == null || subject.asText().isBlank()) {
                throw new IllegalArgumentException("missing subject claim");
            }
            return subject.asText();
        } catch (Exception e) {
            throw new IllegalArgumentException("failed to parse JWT payload", e);
        }
    }
}
