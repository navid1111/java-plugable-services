package com.example.bff.service;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import com.example.bff.config.BffProperties;
import com.example.bff.dto.PostDetail;
import com.example.bff.service.DownstreamViews.CommentSummaryView;
import com.example.bff.service.DownstreamViews.MediaSummaryView;
import com.example.bff.service.DownstreamViews.PostView;

/**
 * Composes post detail from the owning services over HTTP, never their databases. The post
 * owner (tweeter) is the critical dependency: if it 404s the read is a 404, if the post is
 * deleted it is a 410, and if it fails/times out the read is a 502. Comment and media summaries
 * are optional and fetched in bounded parallel within the deadline; if either is slow or failing
 * its section is omitted and named in {@code degraded} rather than failing the whole response.
 */
@Service
public class PostDetailComposer {

    private static final String TARGET_TYPE = "post";
    private static final HexFormat HEX = HexFormat.of();

    private final RestClient tweeter;
    private final RestClient comment;
    private final RestClient media;
    private final ThreadPoolTaskExecutor executor;
    private final long deadlineMs;

    public PostDetailComposer(
            @Qualifier("tweeterClient") RestClient tweeter,
            @Qualifier("commentClient") RestClient comment,
            @Qualifier("mediaClient") RestClient media,
            ThreadPoolTaskExecutor compositionExecutor,
            BffProperties properties) {
        this.tweeter = tweeter;
        this.comment = comment;
        this.media = media;
        this.executor = compositionExecutor;
        this.deadlineMs = properties.getComposition().getDeadline().toMillis();
    }

    public PostDetail compose(long postId, String authorization, String traceparent) {
        String trace = traceparent != null ? traceparent : newTraceparent();

        // Critical dependency: fetch and validate the post first.
        PostView post = fetchPost(postId, authorization, trace);
        if (post.deletedAt() != null) {
            throw new PostGoneException(postId);
        }

        String targetId = String.valueOf(postId);
        Future<CommentSummaryView> commentFuture = executor.submit(commentTask(targetId, authorization, trace));
        Future<MediaSummaryView> mediaFuture = executor.submit(mediaTask(targetId, authorization, trace));

        List<String> degraded = new ArrayList<>();
        long start = System.nanoTime();
        CommentSummaryView commentView = await(commentFuture, deadlineMs, "comments", degraded);
        long remaining = deadlineMs - (System.nanoTime() - start) / 1_000_000L;
        MediaSummaryView mediaView = await(mediaFuture, Math.max(0, remaining), "media", degraded);

        return new PostDetail(
                new PostDetail.PostSection(post.id(), post.content(), post.createdAt(),
                        post.updatedAt(), post.version()),
                new PostDetail.AuthorSection(post.authorUserId(), post.authorUsername()),
                commentView == null ? null : new PostDetail.CommentSummary(commentView.commentCount()),
                mediaView == null ? null : new PostDetail.MediaSummary(mediaView.mediaCount()),
                List.copyOf(degraded));
    }

    private PostView fetchPost(long postId, String authorization, String trace) {
        try {
            PostView post = tweeter.get().uri("/posts/{id}", postId)
                    .headers(h -> forward(h, authorization, trace))
                    .retrieve().body(PostView.class);
            if (post == null) {
                throw new PostNotFoundException(postId);
            }
            return post;
        } catch (HttpClientErrorException.NotFound e) {
            throw new PostNotFoundException(postId);
        } catch (RestClientException e) {
            throw new CriticalDependencyException("tweeter", e);
        }
    }

    private Callable<CommentSummaryView> commentTask(String targetId, String authorization, String trace) {
        return () -> comment.get().uri("/comments/targets/{t}/{id}/summary", TARGET_TYPE, targetId)
                .headers(h -> forward(h, authorization, trace))
                .retrieve().body(CommentSummaryView.class);
    }

    private Callable<MediaSummaryView> mediaTask(String targetId, String authorization, String trace) {
        return () -> media.get().uri("/media/targets/{t}/{id}/summary", TARGET_TYPE, targetId)
                .headers(h -> forward(h, authorization, trace))
                .retrieve().body(MediaSummaryView.class);
    }

    private <T> T await(Future<T> future, long budgetMs, String name, List<String> degraded) {
        try {
            return future.get(budgetMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            degraded.add(name);
            return null;
        } catch (ExecutionException e) {
            degraded.add(name);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            degraded.add(name);
            return null;
        }
    }

    private void forward(HttpHeaders headers, String authorization, String traceparent) {
        if (authorization != null && !authorization.isBlank()) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        headers.set("traceparent", traceparent);
    }

    private static String newTraceparent() {
        return "00-" + randomHex(16) + "-" + randomHex(8) + "-01";
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        ThreadLocalRandom.current().nextBytes(buf);
        return HEX.formatHex(buf);
    }
}
