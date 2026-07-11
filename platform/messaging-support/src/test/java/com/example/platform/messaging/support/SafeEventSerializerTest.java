package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SafeEventSerializerTest {

    private final SafeEventSerializer serializer = new SafeEventSerializer(new ObjectMapper());

    @Test
    void serializesCleanEvent() {
        var event = EventEnvelope.fact(EventTypes.USER_REGISTERED_V1, 1, "auth-service",
                "user", "user-1", 1, null, null, null,
                Map.of("userId", "user-1", "username", "alice"));
        String json = serializer.serialize(event);
        assertTrue(json.contains("alice"));
        assertTrue(json.contains("user.registered.v1"));
    }

    @Test
    void rejectsTopLevelCredentialField() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("userId", "user-1");
        payload.put("passwordHash", "$2a$10$secret");
        var event = EventEnvelope.fact(EventTypes.USER_REGISTERED_V1, 1, "auth-service",
                "user", "user-1", 1, null, null, null, payload);
        assertThrows(SensitiveDataException.class, () -> serializer.serialize(event));
    }

    @Test
    void rejectsNestedCredentialField() {
        var nested = Map.of("provider", "github", "accessToken", "ghp_leak");
        var payload = Map.of("userId", "user-1", "oauth", nested);
        var event = EventEnvelope.fact(EventTypes.USER_REGISTERED_V1, 1, "auth-service",
                "user", "user-1", 1, null, null, null, payload);
        assertThrows(SensitiveDataException.class, () -> serializer.serialize(event));
    }
}
