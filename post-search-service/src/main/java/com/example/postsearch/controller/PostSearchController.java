package com.example.postsearch.controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.postsearch.model.SearchDocument;
import com.example.postsearch.security.JwtHelper;
import com.example.postsearch.service.PostSearchService;


@RestController
@RequestMapping("/post-search")
public class PostSearchController {

    private final PostSearchService search;
    private final JwtHelper jwtHelper;

    public PostSearchController(PostSearchService search, JwtHelper jwtHelper) {
        this.search = search;
        this.jwtHelper = jwtHelper;
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

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }

    private ResponseEntity<Map<String, String>> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", message));
    }
}
