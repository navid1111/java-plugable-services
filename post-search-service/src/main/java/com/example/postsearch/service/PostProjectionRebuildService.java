package com.example.postsearch.service;

import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestClient;

@Service
public class PostProjectionRebuildService {
    private static final String PROJECTION = "post-search";
    private final PostSearchService search;
    private final JdbcTemplate jdbc;
    private final TransactionOperations transactions;
    private final RestClient tweeter;
    private final String token;

    public PostProjectionRebuildService(PostSearchService search, JdbcTemplate jdbc,
            TransactionOperations transactions,
            @Value("${post-export.base-url}") String baseUrl,
            @Value("${internal.service.token}") String token) {
        this.search = search;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.tweeter = RestClient.builder().baseUrl(baseUrl).build();
        this.token = token;
    }

    public record ExportedPost(String postId, String authorUsername, String content,
            Instant createdAt, Instant updatedAt, Instant deletedAt, long aggregateVersion) {}
    public record ExportPage(List<ExportedPost> items, long checkpoint, boolean hasMore) {}
    public record RebuildResult(long checkpoint, int pages, int records, boolean complete) {}

    public RebuildResult rebuild(int requestedMaxPages) {
        int maxPages = Math.max(1, Math.min(requestedMaxPages, 1_000));
        long checkpoint = checkpoint();
        int pages = 0;
        int records = 0;
        boolean more = true;
        while (more && pages < maxPages) {
            ExportPage page = fetch(checkpoint);
            applyPage(page);
            checkpoint = page.checkpoint();
            pages++;
            records += page.items().size();
            more = page.hasMore();
        }
        return new RebuildResult(checkpoint, pages, records, !more);
    }

    private ExportPage fetch(long checkpoint) {
        ExportPage page = tweeter.get()
                .uri(uri -> uri.path("/internal/posts/export")
                        .queryParam("afterId", checkpoint).queryParam("pageSize", 200).build())
                .header("X-Internal-Service-Token", token)
                .retrieve().body(ExportPage.class);
        if (page == null) throw new IllegalStateException("empty post export response");
        return page;
    }

    private void applyPage(ExportPage page) {
        transactions.executeWithoutResult(status -> {
            for (ExportedPost post : page.items()) {
                if (post.deletedAt() == null) {
                    search.applyPostSnapshot(post.postId(), post.authorUsername(), post.content(),
                            post.createdAt(), post.aggregateVersion());
                } else {
                    search.deletePostProjection(post.postId(), post.aggregateVersion(), post.deletedAt());
                }
            }
            jdbc.update("""
                    INSERT INTO projection_rebuild_checkpoints(projection, checkpoint, updated_at)
                    VALUES (?, ?, CURRENT_TIMESTAMP)
                    ON CONFLICT (projection) DO UPDATE
                    SET checkpoint = EXCLUDED.checkpoint, updated_at = EXCLUDED.updated_at
                    """, PROJECTION, page.checkpoint());
        });
    }

    public long checkpoint() {
        List<Long> values = jdbc.query("SELECT checkpoint FROM projection_rebuild_checkpoints WHERE projection = ?",
                (rs, row) -> rs.getLong(1), PROJECTION);
        return values.isEmpty() ? 0 : values.getFirst();
    }

    public void resetCheckpoint() {
        jdbc.update("DELETE FROM projection_rebuild_checkpoints WHERE projection = ?", PROJECTION);
    }
}
