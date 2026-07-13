package com.example.bff.service;

import com.example.bff.config.BffProperties;
import com.example.bff.dto.ComposedFeed;
import com.example.bff.dto.PostDetail;
import com.example.bff.service.DownstreamViews.CommentSummaryView;
import com.example.bff.service.DownstreamViews.MediaSummaryView;
import com.example.bff.service.DownstreamViews.PostView;
import com.example.bff.service.DownstreamViews.TweeterFeedView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Fetches the authoritative cursor page once, then fans out optional comment/media summaries
 * on the bounded composition executor. Every optional call shares one page deadline, avoiding
 * N serial timeouts and preventing a slow dependency from multiplying feed latency.
 */
@Service
public class FeedComposer {
    private static final String TARGET_TYPE = "post";
    private final RestClient tweeter;
    private final RestClient comment;
    private final RestClient media;
    private final ThreadPoolTaskExecutor executor;
    private final long deadlineNanos;

    public FeedComposer(@Qualifier("tweeterClient") RestClient tweeter,
            @Qualifier("commentClient") RestClient comment,
            @Qualifier("mediaClient") RestClient media,
            ThreadPoolTaskExecutor compositionExecutor, BffProperties properties) {
        this.tweeter = tweeter;
        this.comment = comment;
        this.media = media;
        this.executor = compositionExecutor;
        this.deadlineNanos = properties.getComposition().getDeadline().toNanos();
    }

    public ComposedFeed compose(String cursor, int requestedPageSize, String authorization,
            String traceparent) {
        long deadline = System.nanoTime() + deadlineNanos;
        int pageSize = Math.max(1, Math.min(requestedPageSize, 20));
        TweeterFeedView source = fetchSource(cursor, pageSize, authorization, traceparent);
        List<Pending> pending = source.items().stream()
                .filter(post -> post.deletedAt() == null)
                .map(post -> submit(post, authorization, traceparent))
                .toList();

        List<PostDetail> items = new ArrayList<>(pending.size());
        long watermark = 0;
        for (Pending item : pending) {
            List<String> degraded = new ArrayList<>();
            CommentSummaryView comments = await(item.comments(), deadline, "comments", degraded);
            MediaSummaryView mediaView = await(item.media(), deadline, "media", degraded);
            PostView post = item.post();
            watermark = Math.max(watermark, post.version());
            items.add(new PostDetail(
                    new PostDetail.PostSection(post.id(), post.content(), post.createdAt(),
                            post.updatedAt(), post.version()),
                    new PostDetail.AuthorSection(post.authorUserId(), post.authorUsername()),
                    comments == null ? null : new PostDetail.CommentSummary(comments.commentCount()),
                    mediaView == null ? null : new PostDetail.MediaSummary(mediaView.mediaCount()),
                    List.copyOf(degraded)));
        }
        return new ComposedFeed(List.copyOf(items), source.nextCursor(), watermark);
    }

    private TweeterFeedView fetchSource(String cursor, int pageSize, String authorization,
            String traceparent) {
        try {
            TweeterFeedView page = tweeter.get().uri(uri -> {
                        var builder = uri.path("/posts/feed").queryParam("pageSize", pageSize);
                        if (cursor != null && !cursor.isBlank()) builder.queryParam("cursor", cursor);
                        return builder.build();
                    }).headers(headers -> forward(headers, authorization, traceparent))
                    .retrieve().body(TweeterFeedView.class);
            if (page == null || page.items() == null) {
                throw new CriticalDependencyException("tweeter", null);
            }
            return page;
        } catch (RestClientException failure) {
            throw new CriticalDependencyException("tweeter", failure);
        }
    }

    private Pending submit(PostView post, String authorization, String traceparent) {
        String id = String.valueOf(post.id());
        Future<CommentSummaryView> comments = executor.submit(() -> comment.get()
                .uri("/comments/targets/{type}/{id}/summary", TARGET_TYPE, id)
                .headers(headers -> forward(headers, authorization, traceparent))
                .retrieve().body(CommentSummaryView.class));
        Future<MediaSummaryView> mediaFuture = executor.submit(() -> media.get()
                .uri("/media/targets/{type}/{id}/summary", TARGET_TYPE, id)
                .headers(headers -> forward(headers, authorization, traceparent))
                .retrieve().body(MediaSummaryView.class));
        return new Pending(post, comments, mediaFuture);
    }

    private <T> T await(Future<T> future, long deadline, String dependency,
            List<String> degraded) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            future.cancel(true);
            degraded.add(dependency);
            return null;
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            future.cancel(true);
            degraded.add(dependency);
            return null;
        }
    }

    private static void forward(HttpHeaders headers, String authorization, String traceparent) {
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (traceparent != null && !traceparent.isBlank()) headers.set("traceparent", traceparent);
    }

    private record Pending(PostView post, Future<CommentSummaryView> comments,
            Future<MediaSummaryView> media) {}
}
