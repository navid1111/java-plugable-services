package com.example.platform.messaging.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class PostTargetReconciliationClient {
    private final URI baseUri; private final String token; private final ObjectMapper mapper;
    private final TargetProjectionStore targets; private final HttpClient http = HttpClient.newHttpClient();
    public PostTargetReconciliationClient(String baseUrl, String token, ObjectMapper mapper,
            TargetProjectionStore targets) {
        this.baseUri = URI.create(baseUrl); this.token = token; this.mapper = mapper; this.targets = targets;
    }
    public TargetProjectionStore.ReconciliationResult reconcile() {
        List<TargetProjectionStore.AuthoritativeTarget> all = new ArrayList<>();
        long checkpoint = 0; boolean more;
        do {
            JsonNode page = get(checkpoint); more = page.path("hasMore").asBoolean();
            checkpoint = page.path("checkpoint").asLong();
            for (JsonNode post : page.path("items")) {
                boolean active = post.path("deletedAt").isNull();
                String changed = active ? post.path("updatedAt").asText() : post.path("deletedAt").asText();
                all.add(new TargetProjectionStore.AuthoritativeTarget(post.path("postId").asText(),
                        post.path("authorUsername").asText(), post.path("aggregateVersion").asLong(),
                        active, Instant.parse(changed)));
            }
        } while (more);
        return targets.reconcilePosts(all);
    }
    private JsonNode get(long checkpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri.resolve(
                    "/internal/posts/export?afterId=" + checkpoint + "&pageSize=500"))
                    .header("X-Internal-Service-Token", token).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IllegalStateException("post export returned " + response.statusCode());
            return mapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("reconciliation interrupted", e);
        } catch (Exception e) { throw new IllegalStateException("post reconciliation failed", e); }
    }
}
