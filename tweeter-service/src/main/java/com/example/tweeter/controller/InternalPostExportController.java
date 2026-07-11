package com.example.tweeter.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tweeter.model.Post;
import com.example.tweeter.repository.PostRepository;

@RestController
@RequestMapping("/internal/posts")
public class InternalPostExportController {
    private final PostRepository posts;
    private final byte[] expectedToken;

    public InternalPostExportController(PostRepository posts,
            @Value("${internal.service.token}") String expectedToken) {
        this.posts = posts;
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public record ExportedPost(String postId, String authorUsername, String content,
            Instant createdAt, Instant updatedAt, Instant deletedAt, long aggregateVersion) {
        static ExportedPost from(Post post) {
            return new ExportedPost(post.getId().toString(), post.getAuthorUsername(), post.getContent(),
                    post.getCreatedAt(), post.getUpdatedAt(), post.getDeletedAt(), post.getVersion() + 1);
        }
    }

    public record ExportPage(List<ExportedPost> items, long checkpoint, boolean hasMore) {}

    @GetMapping("/export")
    public ResponseEntity<?> export(
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "200") int pageSize) {
        if (token == null || !MessageDigest.isEqual(expectedToken,
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        int size = Math.max(1, Math.min(pageSize, 500));
        List<Post> fetched = posts.exportAfter(Math.max(0, afterId), size + 1);
        boolean hasMore = fetched.size() > size;
        List<ExportedPost> items = fetched.stream().limit(size).map(ExportedPost::from).toList();
        long checkpoint = items.isEmpty() ? afterId
                : Long.parseLong(items.get(items.size() - 1).postId());
        return ResponseEntity.ok(new ExportPage(items, checkpoint, hasMore));
    }
}
