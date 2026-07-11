package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class JwtIdentityTest {
    @Test void newAndLegacyTokensRemainCompatible() {
        String id = UUID.randomUUID().toString();
        JwtIdentity current = JwtIdentity.parse(token("{\"sub\":\"" + id + "\",\"username\":\"alice\"}"), new ObjectMapper());
        assertEquals(id, current.userId()); assertEquals("alice", current.username()); assertFalse(current.legacyToken());
        JwtIdentity legacy = JwtIdentity.parse(token("{\"sub\":\"alice\"}"), new ObjectMapper());
        assertNull(legacy.userId()); assertEquals("alice", legacy.username()); assertTrue(legacy.legacyToken());
    }
    private String token(String payload) {
        return "Bearer e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }
}
