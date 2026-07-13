package com.example.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.example.bff.dto.PostDetail;

/**
 * Verifies the BFF composition contract against stubbed owning services (WireMock): a single
 * client call returns the composed post detail; a slow/failing optional dependency yields a
 * documented partial response (200 with the section omitted and named in {@code degraded});
 * and post absence/deletion/owner-failure map to RFC 9457 problem responses.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PostDetailCompositionTest {

    private static final String ALICE_ID = "00000000-0000-0000-0000-000000000001";

    static WireMockServer wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @LocalServerPort int port;
    RestClient client;

    record Response(int status, String contentType, String body) {}

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        wm.start();
        registry.add("bff.downstream.tweeter.base-url", wm::baseUrl);
        registry.add("bff.downstream.comment.base-url", wm::baseUrl);
        registry.add("bff.downstream.media.base-url", wm::baseUrl);
        // Keep the test fast; the timeouts still exceed the stub delays used below.
        registry.add("bff.composition.deadline", () -> "800ms");
        registry.add("bff.downstream.read-timeout", () -> "600ms");
    }

    @AfterAll
    static void stop() {
        wm.stop();
    }

    @BeforeEach
    void reset() {
        wm.resetAll();
        client = RestClient.create("http://localhost:" + port);
    }

    private PostDetail getDetail(long id) {
        return client.get().uri("/bff/posts/{id}", id).retrieve().body(PostDetail.class);
    }

    private Response getRaw(long id) {
        return client.get().uri("/bff/posts/{id}", id).exchange((request, response) ->
                new Response(response.getStatusCode().value(),
                        String.valueOf(response.getHeaders().getContentType()),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8)));
    }

    private void stubPost(long id, String deletedAt) {
        String deleted = deletedAt == null ? "null" : "\"" + deletedAt + "\"";
        wm.stubFor(get(urlPathEqualTo("/posts/" + id)).willReturn(okJson("""
                {"id":%d,"authorUserId":"%s","authorUsername":"alice","content":"hello world",
                 "createdAt":"2026-07-12T00:00:00Z","updatedAt":"2026-07-12T00:00:00Z",
                 "deletedAt":%s,"version":1}""".formatted(id, ALICE_ID, deleted))));
    }

    private void stubCommentSummary(long id, long count) {
        wm.stubFor(get(urlPathEqualTo("/comments/targets/post/" + id + "/summary"))
                .willReturn(okJson("{\"targetType\":\"post\",\"targetId\":\"" + id
                        + "\",\"commentCount\":" + count + "}")));
    }

    private void stubMediaSummary(long id, long count) {
        wm.stubFor(get(urlPathEqualTo("/media/targets/post/" + id + "/summary"))
                .willReturn(okJson("{\"targetType\":\"post\",\"targetId\":\"" + id
                        + "\",\"mediaCount\":" + count + "}")));
    }

    @Test
    void composesAllSectionsInOneCall() {
        stubPost(1, null);
        stubCommentSummary(1, 3);
        stubMediaSummary(1, 2);

        PostDetail body = getDetail(1);

        assertNotNull(body);
        assertEquals(1, body.post().id());
        assertEquals("hello world", body.post().content());
        assertEquals(ALICE_ID, body.author().userId());
        assertEquals("alice", body.author().username());
        assertEquals(3, body.comments().commentCount());
        assertEquals(2, body.media().mediaCount());
        assertTrue(body.degraded().isEmpty(), "no section degraded");
    }

    @Test
    void returnsPartialResponseWhenOptionalDependencyFails() {
        stubPost(1, null);
        stubCommentSummary(1, 5);
        // Media summary fails — must not fail the whole response.
        wm.stubFor(get(urlPathEqualTo("/media/targets/post/1/summary"))
                .willReturn(aResponse().withStatus(500)));

        PostDetail body = getDetail(1);

        assertNotNull(body);
        assertEquals(5, body.comments().commentCount(), "critical + healthy optional still served");
        assertNull(body.media(), "failed optional section is omitted");
        assertTrue(body.degraded().contains("media"), "degraded names the missing section");
    }

    @Test
    void returnsPartialResponseWhenOptionalDependencyTimesOut() {
        stubPost(1, null);
        stubMediaSummary(1, 7);
        // Comment summary is slower than the read timeout -> degraded, not a failure.
        wm.stubFor(get(urlPathEqualTo("/comments/targets/post/1/summary"))
                .willReturn(okJson("{\"commentCount\":9}").withFixedDelay(2000)));

        PostDetail body = getDetail(1);

        assertNull(body.comments());
        assertEquals(7, body.media().mediaCount());
        assertTrue(body.degraded().contains("comments"));
    }

    @Test
    void missingPostIsRfc9457NotFound() {
        wm.stubFor(get(urlPathEqualTo("/posts/2")).willReturn(aResponse().withStatus(404)));

        Response response = getRaw(2);

        assertEquals(404, response.status());
        assertTrue(response.contentType().contains("application/problem+json"), response.contentType());
        assertTrue(response.body().contains("Post not found"));
    }

    @Test
    void deletedPostIsGone() {
        stubPost(3, "2026-07-12T01:00:00Z");

        Response response = getRaw(3);

        assertEquals(410, response.status());
        assertTrue(response.body().contains("Post deleted"));
    }

    @Test
    void criticalDependencyFailureIsBadGateway() {
        wm.stubFor(get(urlPathEqualTo("/posts/4")).willReturn(aResponse().withStatus(500)));

        Response response = getRaw(4);

        assertEquals(502, response.status());
        assertTrue(response.body().contains("critical-dependency-unavailable"));
        assertTrue(response.body().contains("\"dependency\":\"tweeter\""));
    }
}
