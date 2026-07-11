package com.example.platform.messaging;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SchemaResourceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void schemasArePackagedAndValidJson() throws Exception {
        for (String name : new String[]{"event-envelope-v1.schema.json","post-snapshot-v1.schema.json"}) {
            try (var in = getClass().getResourceAsStream("/schemas/" + name)) {
                assertNotNull(in, name);
                var schema = mapper.readTree(in);
                assertEquals("object", schema.get("type").asText());
                assertTrue(schema.get("required").isArray());
            }
        }
    }
}
