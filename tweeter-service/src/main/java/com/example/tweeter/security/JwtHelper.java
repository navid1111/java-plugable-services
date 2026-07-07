package com.example.tweeter.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Component
public class JwtHelper {
    private final ObjectMapper objectMapper = new ObjectMapper();

    public String extractUsername(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Missing or invalid Authorization header");
        }
        
        String token = authHeader.substring(7);
        String[] chunks = token.split("\\.");
        if (chunks.length < 2) {
            throw new IllegalArgumentException("Invalid JWT format");
        }
        
        try {
            String payload = new String(Base64.getUrlDecoder().decode(chunks[1]));
            JsonNode jsonNode = objectMapper.readTree(payload);
            return jsonNode.get("sub").asText();
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JWT payload", e);
        }
    }
}
