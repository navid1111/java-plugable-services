package com.example.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.bff.dto.ComposedFeed;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FeedCompositionTest {
    static final WireMockServer wm = new WireMockServer(
            WireMockConfiguration.options().dynamicPort());

    @LocalServerPort int port;
    RestClient client;

    @DynamicPropertySource
    static void downstreams(DynamicPropertyRegistry registry) {
        wm.start();
        registry.add("bff.downstream.tweeter.base-url", wm::baseUrl);
        registry.add("bff.downstream.comment.base-url", wm::baseUrl);
        registry.add("bff.downstream.media.base-url", wm::baseUrl);
        registry.add("bff.composition.deadline", () -> "900ms");
        registry.add("bff.downstream.read-timeout", () -> "700ms");
        registry.add("bff.composition.pool-size", () -> "16");
    }

    @AfterAll static void stop() { wm.stop(); }

    @BeforeEach
    void reset() {
        wm.resetAll();
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void boundedParallelFeedMeetsLatencyAndCarriesFreshnessWatermark() {
        wm.stubFor(get(urlPathEqualTo("/posts/feed")).willReturn(okJson("""
                {"items":[
                  {"id":1,"authorUserId":"user-1","authorUsername":"alice","content":"one","createdAt":"2026-07-12T00:00:00Z","updatedAt":"2026-07-12T00:00:01Z","deletedAt":null,"version":4},
                  {"id":2,"authorUserId":"user-2","authorUsername":"bob","content":"two","createdAt":"2026-07-12T00:00:00Z","updatedAt":"2026-07-12T00:00:02Z","deletedAt":null,"version":7},
                  {"id":3,"authorUserId":"user-3","authorUsername":"carol","content":"three","createdAt":"2026-07-12T00:00:00Z","updatedAt":"2026-07-12T00:00:03Z","deletedAt":null,"version":5}
                ],"nextCursor":"cursor-2"}
                """)));
        for (long id = 1; id <= 3; id++) {
            wm.stubFor(get(urlPathEqualTo("/comments/targets/post/" + id + "/summary"))
                    .willReturn(okJson("{\"targetType\":\"post\",\"targetId\":\"" + id
                            + "\",\"commentCount\":" + id + "}").withFixedDelay(180)));
            wm.stubFor(get(urlPathEqualTo("/media/targets/post/" + id + "/summary"))
                    .willReturn(okJson("{\"targetType\":\"post\",\"targetId\":\"" + id
                            + "\",\"mediaCount\":" + id + "}").withFixedDelay(180)));
        }

        long started = System.nanoTime();
        ComposedFeed feed = client.get().uri("/bff/feed?pageSize=3")
                .retrieve().body(ComposedFeed.class);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertNotNull(feed);
        assertEquals(3, feed.items().size());
        assertEquals("cursor-2", feed.nextCursor());
        assertEquals("user-1", feed.items().getFirst().author().userId());
        assertEquals(7, feed.sourceVersionWatermark(),
                "watermark exposes the freshest authoritative post version in the page");
        assertTrue(feed.items().stream().allMatch(item -> item.degraded().isEmpty()));
        assertTrue(elapsed.compareTo(Duration.ofMillis(850)) < 0,
                "parallel composition exceeded latency SLO: " + elapsed);
    }

    @Test
    void oneOptionalFailureDegradesOnlyThatFeedItemSection() {
        wm.stubFor(get(urlPathEqualTo("/posts/feed")).willReturn(okJson("""
                {"items":[{"id":9,"authorUserId":"user-9","authorUsername":"alice","content":"nine",
                "createdAt":"2026-07-12T00:00:00Z","updatedAt":"2026-07-12T00:00:01Z",
                "deletedAt":null,"version":2}],"nextCursor":null}
                """)));
        wm.stubFor(get(urlPathEqualTo("/comments/targets/post/9/summary"))
                .willReturn(okJson("{\"commentCount\":2}")));
        wm.stubFor(get(urlPathEqualTo("/media/targets/post/9/summary"))
                .willReturn(aResponse().withStatus(503)));

        ComposedFeed feed = client.get().uri("/bff/feed?pageSize=1")
                .retrieve().body(ComposedFeed.class);

        assertNotNull(feed);
        assertEquals(2, feed.items().getFirst().comments().commentCount());
        assertTrue(feed.items().getFirst().degraded().contains("media"));
    }
}
