package com.example.tweeter.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.tweeter.model.Post;
import com.example.tweeter.security.JwtHelper;
import com.example.tweeter.service.PostService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/posts")
public class PostController {

    private final PostService posts;
    private final JwtHelper jwtHelper;

    public PostController(PostService posts, JwtHelper jwtHelper) {
        this.posts = posts;
        this.jwtHelper = jwtHelper;
    }

    public record CreatePostRequest(
            @NotBlank(message = "content is required")
            @Size(max = 280, message = "content must be 280 characters or fewer")
            String content) {
    }

    public record UpdatePostRequest(
            @NotBlank @Size(max = 280) String content,
            long expectedVersion) {
    }

    public record PostResponse(Long id, String authorUserId, String authorUsername, String content, Instant createdAt,
            Instant updatedAt, Instant deletedAt, long version) {
        static PostResponse from(Post post) {
            return new PostResponse(post.getId(), post.getAuthorUserId(), post.getAuthorUsername(), post.getContent(),
                    post.getCreatedAt(), post.getUpdatedAt(), post.getDeletedAt(), post.getVersion() + 1);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id, @Valid @RequestBody UpdatePostRequest body) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            return ResponseEntity.ok(PostResponse.from(
                    posts.update(id, identity.userId(), body.content(), body.expectedVersion())));
        } catch (OptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id, @RequestParam long expectedVersion) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            posts.delete(id, identity.userId(), expectedVersion);
            return ResponseEntity.noContent().build();
        } catch (OptimisticLockingFailureException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    public record FeedResponse(List<PostResponse> items, String nextCursor) {
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreatePostRequest body) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            Post post = posts.create(identity.userId(), identity.username(), body.content().trim());
            return ResponseEntity.status(HttpStatus.CREATED).body(PostResponse.from(post));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(@PathVariable Long id) {
        return posts.findById(id)
                .<ResponseEntity<?>>map(post -> ResponseEntity.ok(PostResponse.from(post)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "post not found")));
    }

    @GetMapping
    public ResponseEntity<?> byAuthor(@RequestParam String authorUserId) {
        try {
            return ResponseEntity.ok(posts.findByAuthorUserId(authorUserId).stream()
                    .map(PostResponse::from).toList());
        } catch (IllegalArgumentException e) { return badRequest(e.getMessage()); }
    }

    @PutMapping("/users/{userId}/follow")
    public ResponseEntity<?> follow(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String userId,
            @RequestParam String username) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            posts.follow(identity.userId(), identity.username(), userId, username);
            return ResponseEntity.ok(Map.of("followerUserId", identity.userId(), "followeeUserId", userId));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/users/{userId}/follow")
    public ResponseEntity<?> unfollow(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String userId) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            posts.unfollow(identity.userId(), userId);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/feed")
    public ResponseEntity<?> feed(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            PostService.FeedPage page = posts.feed(identity.userId(), cursor, pageSize);
            List<PostResponse> items = page.items().stream()
                    .map(PostResponse::from)
                    .toList();
            return ResponseEntity.ok(new FeedResponse(items, page.nextCursor()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
