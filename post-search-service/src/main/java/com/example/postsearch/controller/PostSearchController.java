package com.example.postsearch.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.postsearch.model.SearchDocument;
import com.example.postsearch.security.JwtHelper;
import com.example.postsearch.service.PostSearchService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/post-search")
public class PostSearchController {

    private final PostSearchService search;
    private final JwtHelper jwtHelper;

    public PostSearchController(PostSearchService search, JwtHelper jwtHelper) {
        this.search = search;
        this.jwtHelper = jwtHelper;
    }

    public record UpsertDocumentRequest(
            @NotBlank(message = "authorUsername is required")
            @Size(max = 100, message = "authorUsername must be 100 characters or fewer")
            String authorUsername,

            @NotBlank(message = "content is required")
            @Size(max = 2000, message = "content must be 2000 characters or fewer")
            String content,

            @NotNull(message = "createdAt is required")
            Instant createdAt) {
    }

    public record UpdateLikeCountRequest(Integer likeCount) {
    }

    public record SearchDocumentResponse(
            Long id,
            String targetType,
            String targetId,
            String authorUsername,
            String content,
            Instant createdAt,
            int likeCount,
            Instant indexedAt) {
        static SearchDocumentResponse from(SearchDocument document) {
            return new SearchDocumentResponse(
                    document.getId(),
                    document.getTargetType(),
                    document.getTargetId(),
                    document.getAuthorUsername(),
                    document.getContent(),
                    document.getCreatedAt(),
                    document.getLikeCount(),
                    document.getIndexedAt());
        }
    }

    public record SearchResponse(List<SearchDocumentResponse> items, String nextCursor) {
    }

    @PutMapping("/documents/{targetType}/{targetId}")
    public ResponseEntity<?> upsertDocument(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType,
            @PathVariable String targetId,
            @Valid @RequestBody UpsertDocumentRequest body) {
        try {
            jwtHelper.extractUsername(authorization);
            SearchDocument document = search.upsertDocument(
                    targetType, targetId, body.authorUsername(), body.content(), body.createdAt());
            return ResponseEntity.ok(SearchDocumentResponse.from(document));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/documents/{targetType}/{targetId}")
    public ResponseEntity<?> getDocument(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType,
            @PathVariable String targetId) {
        try {
            jwtHelper.extractUsername(authorization);
            return search.findByTarget(targetType, targetId)
                    .<ResponseEntity<?>>map(document -> ResponseEntity.ok(SearchDocumentResponse.from(document)))
                    .orElseGet(() -> notFound("search document not found"));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping
    public ResponseEntity<?> search(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam String q,
            @RequestParam(defaultValue = "recency") String sort,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            jwtHelper.extractUsername(authorization);
            PostSearchService.SearchPage page = search.search(q, sort, cursor, pageSize);
            List<SearchDocumentResponse> items = page.items().stream()
                    .map(SearchDocumentResponse::from)
                    .toList();
            return ResponseEntity.ok(new SearchResponse(items, page.nextCursor()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PutMapping("/documents/{targetType}/{targetId}/like-count")
    public ResponseEntity<?> updateLikeCount(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable String targetType,
            @PathVariable String targetId,
            @RequestBody(required = false) UpdateLikeCountRequest body) {
        try {
            jwtHelper.extractUsername(authorization);
            Integer likeCount = body == null ? null : body.likeCount();
            SearchDocument document = search.updateLikeCount(targetType, targetId, likeCount);
            return ResponseEntity.ok(SearchDocumentResponse.from(document));
        } catch (PostSearchService.NotFoundException e) {
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
