package com.example.platform.messaging;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SchemaResourceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void schemasArePackagedAndValidJson() throws Exception {
        for (String name : new String[]{
                "event-envelope-v1.schema.json","post-snapshot-v1.schema.json",
                "post-deleted-v1.schema.json","follow-changed-v1.schema.json",
                "user-registered-v1.schema.json","media-uploaded-v1.schema.json",
                "comment-created-v1.schema.json", "leetcode-judge-requested-v1.schema.json",
                "leetcode-judge-completed-v1.schema.json"}) {
            try (var in = getClass().getResourceAsStream("/schemas/" + name)) {
                assertNotNull(in, name);
                var schema = mapper.readTree(in);
                assertEquals("object", schema.get("type").asText());
                assertTrue(schema.get("required").isArray());
            }
        }
    }
}
