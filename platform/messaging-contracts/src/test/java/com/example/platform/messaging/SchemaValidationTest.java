package com.example.platform.messaging;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves the contract: every fixture under fixtures/valid passes both the envelope
 * schema and its event-type payload schema, and every fixture under fixtures/invalid
 * fails at least one of them. Uses a real Draft 2020-12 validator so structural
 * guarantees (required fields, enums, closed objects, credential leakage) are enforced
 * — not merely that the schema files are syntactically valid JSON.
 */
class SchemaValidationTest {

    // Jackson 2 mapper: the networknt validator operates on com.fasterxml JsonNode.
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    private final JsonSchema envelopeSchema = schema("event-envelope-v1");

    /** Payload schema per registered event type. */
    private final Map<String, JsonSchema> payloadSchemas = Map.of(
            EventTypes.POST_CREATED_V1, schema("post-snapshot-v1"),
            EventTypes.POST_UPDATED_V1, schema("post-snapshot-v1"),
            EventTypes.POST_DELETED_V1, schema("post-deleted-v1"),
            EventTypes.FOLLOW_CREATED_V1, schema("follow-changed-v1"),
            EventTypes.FOLLOW_DELETED_V1, schema("follow-changed-v1"),
            EventTypes.USER_REGISTERED_V1, schema("user-registered-v1"),
            EventTypes.MEDIA_UPLOADED_V1, schema("media-uploaded-v1"),
            EventTypes.COMMENT_CREATED_V1, schema("comment-created-v1"));

    @Test
    void validFixturesPassEnvelopeAndPayloadSchemas() throws Exception {
        List<Path> fixtures = fixtures("valid");
        assertFalse(fixtures.isEmpty(), "no valid fixtures found");
        for (Path fixture : fixtures) {
            Set<ValidationMessage> errors = validate(fixture);
            assertTrue(errors.isEmpty(),
                    () -> fixture.getFileName() + " should be valid but reported: " + errors);
        }
    }

    @Test
    void breakingFixturesFailSchemaValidation() throws Exception {
        List<Path> fixtures = fixtures("invalid");
        assertFalse(fixtures.isEmpty(), "no invalid fixtures found");
        for (Path fixture : fixtures) {
            Set<ValidationMessage> errors = validate(fixture);
            assertFalse(errors.isEmpty(),
                    () -> fixture.getFileName() + " should have failed schema validation but passed");
        }
    }

    /** Validate a full event: envelope schema over the whole node, payload schema over payload. */
    private Set<ValidationMessage> validate(Path fixture) throws Exception {
        JsonNode event = mapper.readTree(Files.readString(fixture));
        Set<ValidationMessage> errors = new LinkedHashSet<>(envelopeSchema.validate(event));
        JsonNode eventType = event.get("eventType");
        assertNotNull(eventType, () -> fixture + " is missing eventType");
        JsonSchema payloadSchema = payloadSchemas.get(eventType.asText());
        assertNotNull(payloadSchema,
                () -> "no payload schema registered for " + eventType.asText());
        JsonNode payload = event.get("payload");
        errors.addAll(payloadSchema.validate(payload == null ? mapper.nullNode() : payload));
        return errors;
    }

    private JsonSchema schema(String name) {
        try (var in = getClass().getResourceAsStream("/schemas/" + name + ".schema.json")) {
            assertNotNull(in, "missing schema resource: " + name);
            return factory.getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("could not load schema " + name, e);
        }
    }

    private List<Path> fixtures(String kind) throws Exception {
        Path dir = Path.of(getClass().getResource("/fixtures/" + kind).toURI());
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }
}
