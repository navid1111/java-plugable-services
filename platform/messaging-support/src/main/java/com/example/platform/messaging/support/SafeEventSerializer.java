package com.example.platform.messaging.support;

import com.example.platform.messaging.EventEnvelope;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Serializes an {@link EventEnvelope} to the JSON persisted in the outbox, refusing any
 * event that carries prohibited data. The forbidden-key scan walks the whole serialized
 * tree (envelope and payload, at any depth) so a leak buried in a nested object is caught
 * before it is ever written. This is the enforcement point behind the event catalog's rule
 * that passwords, hashes, bearer tokens, and private keys never appear in any event.
 */
public class SafeEventSerializer {

    /** Field names that must never appear in a published event, matched case-insensitively. */
    private static final Pattern FORBIDDEN = Pattern.compile(
            "(?i).*(password|passwordhash|secret|token|authorization|bearer|"
                    + "private[_-]?key|api[_-]?key|credential).*");

    private final ObjectMapper mapper;

    public SafeEventSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serialize(EventEnvelope<?> envelope) {
        JsonNode tree = mapper.valueToTree(envelope);
        reject(tree, "");
        return mapper.writeValueAsString(envelope);
    }

    private void reject(JsonNode node, String path) {
        if (node.isObject()) {
            for (var entry : node.properties()) {
                String key = entry.getKey();
                if (FORBIDDEN.matcher(key).matches()) {
                    throw new SensitiveDataException(
                            "prohibited field '" + key + "' at " + path + "/" + key);
                }
                reject(entry.getValue(), path + "/" + key);
            }
        } else if (node.isArray()) {
            int i = 0;
            for (JsonNode child : node) {
                reject(child, path + "/" + i++);
            }
        }
    }

    /** Exposed for callers that want to assert a set of keys is rejected in tests. */
    public static Set<String> forbiddenExamples() {
        return Set.of("password", "passwordHash", "token", "authorization", "privateKey", "apiKey");
    }
}
