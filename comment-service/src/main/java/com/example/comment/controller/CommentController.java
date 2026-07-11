package com.example.comment.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.comment.model.Comment;
import com.example.comment.security.JwtHelper;
import com.example.comment.service.CommentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/comments")
public class CommentController {

    private final CommentService comments;
    private final JwtHelper jwtHelper;

    public CommentController(CommentService comments, JwtHelper jwtHelper) {
        this.comments = comments;
        this.jwtHelper = jwtHelper;
    }

    public record CreateCommentRequest(
            @NotBlank(message = "content is required")
            @Size(max = 500, message = "content must be 500 characters or fewer")
            String content) {
    }

    public record CommentResponse(
            Long id,
            String targetType,
            String targetId,
            String authorUsername,
            String content,
            Instant createdAt) {
        static CommentResponse from(Comment comment) {
            return new CommentResponse(
                    comment.getId(),
                    comment.getTargetType(),
                    comment.getTargetId(),
                    comment.getAuthorUsername(),
                    comment.getContent(),
                    comment.getCreatedAt());
        }
    }

    public record TargetCommentsResponse(List<CommentResponse> items, String nextCursor) {
    }
    public record TargetCommentSummary(String targetType, String targetId, long commentCount) {}

    @GetMapping("/targets/{targetType}/{targetId}/summary")
    public ResponseEntity<?> summary(@RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType, @PathVariable String targetId) {
        try {
            jwtHelper.extractUsername(authorization);
            return ResponseEntity.ok(new TargetCommentSummary(targetType, targetId,
                    comments.countForActiveTarget(targetType, targetId)));
        } catch (IllegalArgumentException e) { return badRequest(e.getMessage()); }
    }

    @PostMapping("/targets/{targetType}/{targetId}")
    public ResponseEntity<?> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType,
            @PathVariable String targetId,
            @Valid @RequestBody CreateCommentRequest body) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            Comment comment = comments.create(username, targetType, targetId, body.content());
            return ResponseEntity.status(HttpStatus.CREATED).body(CommentResponse.from(comment));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        try {
            jwtHelper.extractUsername(authorization);
            return comments.findById(id)
                    .<ResponseEntity<?>>map(comment -> ResponseEntity.ok(CommentResponse.from(comment)))
                    .orElseGet(() -> notFound("comment not found"));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/targets/{targetType}/{targetId}")
    public ResponseEntity<?> byTarget(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType,
            @PathVariable String targetId,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            jwtHelper.extractUsername(authorization);
            CommentService.CommentPage page = comments.findByTarget(targetType, targetId, cursor, pageSize);
            List<CommentResponse> items = page.items().stream()
                    .map(CommentResponse::from)
                    .toList();
            return ResponseEntity.ok(new TargetCommentsResponse(items, page.nextCursor()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            comments.deleteOwn(username, id);
            return ResponseEntity.noContent().build();
        } catch (CommentService.ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (CommentService.NotFoundException e) {
            return notFound(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, String>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", message));
    }
}
