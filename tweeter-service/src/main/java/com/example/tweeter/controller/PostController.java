package com.example.tweeter.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    public record PostResponse(Long id, String authorUsername, String content, Instant createdAt) {
        static PostResponse from(Post post) {
            return new PostResponse(post.getId(), post.getAuthorUsername(), post.getContent(), post.getCreatedAt());
        }
    }

    public record FeedResponse(List<PostResponse> items, String nextCursor) {
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreatePostRequest body) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            Post post = posts.create(username, body.content().trim());
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
    public ResponseEntity<?> byAuthor(@RequestParam String author) {
        if (author == null || author.trim().isEmpty()) {
            return badRequest("author is required");
        }

        List<PostResponse> responses = posts.findByAuthor(author.trim()).stream()
                .map(PostResponse::from)
                .toList();
        return ResponseEntity.ok(responses);
    }

    @PutMapping("/users/{username}/follow")
    public ResponseEntity<?> follow(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String username) {
        try {
            String follower = jwtHelper.extractUsername(authorization);
            posts.follow(follower, username);
            return ResponseEntity.ok(Map.of("followerUsername", follower, "followeeUsername", username));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/users/{username}/follow")
    public ResponseEntity<?> unfollow(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String username) {
        try {
            String follower = jwtHelper.extractUsername(authorization);
            posts.unfollow(follower, username);
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
            String username = jwtHelper.extractUsername(authorization);
            PostService.FeedPage page = posts.feed(username, cursor, pageSize);
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
