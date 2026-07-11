package com.example.platform.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Protects published v1 contracts from producer/consumer drift.
 *
 * <p>A published version is structurally immutable. Documentation-only keywords may change, but
 * validation rules require a new schema/event version. This deliberately enforces full
 * compatibility: old consumers keep accepting new producer messages and new consumers keep
 * accepting retained/replayed messages.</p>
 */
class SchemaCompatibilityTest {

    private static final Set<String> DOCUMENTATION_KEYWORDS = Set.of(
            "title", "description", "examples", "$comment", "deprecated");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void publishedV1SchemasRemainStructurallyCompatible() throws Exception {
        List<String> published = schemaNames("/compatibility-baseline/schemas");
        List<String> current = schemaNames("/schemas");
        assertEquals(published, current,
                "schema versions must be added to both the published baseline and current catalog");

        for (String name : published) {
            JsonNode baseline = normalized(resource("/compatibility-baseline/schemas/" + name));
            JsonNode candidate = normalized(resource("/schemas/" + name));
            assertEquals(baseline, candidate,
                    () -> name + " changed structurally; publish a new event/schema version instead");
        }
    }

    @Test
    void incompatibleRequiredFieldChangeIsDetected() throws Exception {
        JsonNode baseline = normalized(resource("/schemas/post-snapshot-v1.schema.json"));
        ObjectNode candidate = baseline.deepCopy();
        ((ArrayNode) candidate.withArray("required")).add("newRequiredField");

        assertNotEquals(baseline, normalized(candidate),
                "the compatibility gate must reject a newly required field");
    }

    @Test
    void documentationOnlyChangesRemainAllowed() throws Exception {
        JsonNode baseline = normalized(resource("/schemas/post-snapshot-v1.schema.json"));
        ObjectNode candidate = resource("/schemas/post-snapshot-v1.schema.json").deepCopy();
        candidate.put("description", "Updated contract documentation");

        assertEquals(baseline, normalized(candidate));
    }

    private JsonNode resource(String path) throws IOException {
        try (var in = getClass().getResourceAsStream(path)) {
            assertNotNull(in, "missing schema resource: " + path);
            return mapper.readTree(in);
        }
    }

    private List<String> schemaNames(String resourceDirectory)
            throws URISyntaxException, IOException {
        Path directory = Path.of(getClass().getResource(resourceDirectory).toURI());
        try (var files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".schema.json"))
                    .map(path -> path.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private JsonNode normalized(JsonNode source) {
        JsonNode copy = source.deepCopy();
        removeDocumentation(copy);
        return copy;
    }

    private void removeDocumentation(JsonNode node) {
        if (node instanceof ObjectNode object) {
            DOCUMENTATION_KEYWORDS.forEach(object::remove);
            object.elements().forEachRemaining(this::removeDocumentation);
        } else if (node instanceof ArrayNode array) {
            array.elements().forEachRemaining(this::removeDocumentation);
        }
    }
}
