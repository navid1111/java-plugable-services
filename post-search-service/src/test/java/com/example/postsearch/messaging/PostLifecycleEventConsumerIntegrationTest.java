package com.example.postsearch.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.post.PostDeleted;
import com.example.platform.messaging.post.PostSnapshot;
import com.example.platform.messaging.support.InboxMessageRepository;
import com.example.postsearch.repository.SearchDocumentRepository;
import com.example.postsearch.repository.SearchTermEntryRepository;
import com.example.postsearch.service.PostProjectionRebuildService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Testcontainers(disabledWithoutDocker = true)
class PostLifecycleEventConsumerIntegrationTest {
    @Container static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine");
    static HttpServer exportServer;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("post-export.base-url", () -> startExportServer());
    }

    @Autowired PostLifecycleEventConsumer consumer;
    @Autowired SearchDocumentRepository documents;
    @Autowired SearchTermEntryRepository terms;
    @Autowired InboxMessageRepository inbox;
    @Autowired ObjectMapper mapper;
    @Autowired PostProjectionRebuildService rebuild;

    @BeforeEach
    void clear() {
        inbox.deleteAll();
        terms.deleteAll();
        documents.deleteAll();
    }

    @Test
    void projectionTermsAndInboxCommitAtomicallyAndDuplicatesAreIgnored() {
        String created = snapshot(EventTypes.POST_CREATED_V1, 1, "first searchable body");
        assertThat(consumer.process(created)).isTrue();
        assertThat(consumer.process(created)).isFalse();

        var document = documents.findByTargetTypeAndTargetId("post", "42").orElseThrow();
        assertThat(document.getAggregateVersion()).isEqualTo(1);
        assertThat(terms.count()).isEqualTo(3);
        assertThat(inbox.count()).isOne();
    }

    @Test
    void outOfOrderEventsCannotRegressAndDeleteTombstonesProjection() {
        consumer.process(snapshot(EventTypes.POST_CREATED_V1, 1, "original searchable body"));
        consumer.process(snapshot(EventTypes.POST_UPDATED_V1, 3, "newest searchable content"));
        consumer.process(snapshot(EventTypes.POST_UPDATED_V1, 2, "stale content"));

        var newest = documents.findByTargetTypeAndTargetId("post", "42").orElseThrow();
        assertThat(newest.getContent()).isEqualTo("newest searchable content");
        assertThat(newest.getAggregateVersion()).isEqualTo(3);

        consumer.process(deleted(4));
        assertThat(documents.findByTargetTypeAndTargetId("post", "42").orElseThrow().isDeleted()).isTrue();
        assertThat(documents.findByTargetTypeAndTargetIdAndDeletedAtIsNull("post", "42")).isEmpty();
        assertThat(terms.count()).isZero();
    }

    @Test
    void emptyProjectionRebuildsFromAuthoritativeExportAndPersistsCheckpoint() {
        rebuild.resetCheckpoint();
        var result = rebuild.rebuild(10);

        assertThat(result.complete()).isTrue();
        assertThat(result.records()).isEqualTo(2);
        assertThat(result.checkpoint()).isEqualTo(2);
        assertThat(documents.count()).isEqualTo(2);
        assertThat(rebuild.checkpoint()).isEqualTo(2);
    }

    private static synchronized String startExportServer() {
        if (exportServer != null) return "http://localhost:" + exportServer.getAddress().getPort();
        try {
            exportServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            exportServer.createContext("/internal/posts/export", exchange -> {
                boolean second = exchange.getRequestURI().getQuery().contains("afterId=1");
                String id = second ? "2" : "1";
                String content = second ? "second authoritative post" : "first authoritative post";
                String response = """
                        {"items":[{"postId":"%s","authorUsername":"alice","content":"%s",
                        "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z",
                        "deletedAt":null,"aggregateVersion":1}],"checkpoint":%s,"hasMore":%s}
                        """.formatted(id, content, id, !second);
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            exportServer.start();
            return "http://localhost:" + exportServer.getAddress().getPort();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String snapshot(String type, long version, String content) {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        return json(EventEnvelope.fact(type, 1, "tweeter-service", "post", "42", version,
                UUID.randomUUID(), null, null,
                new PostSnapshot("42", null, "alice", content, "public", now, now)));
    }

    private String deleted(long version) {
        return json(EventEnvelope.fact(EventTypes.POST_DELETED_V1, 1, "tweeter-service", "post", "42",
                version, UUID.randomUUID(), null, null,
                new PostDeleted("42", "legacy:alice", Instant.parse("2026-01-02T00:00:00Z"))));
    }

    private String json(Object value) { return mapper.writeValueAsString(value); }
}
