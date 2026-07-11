package com.example.postsearch.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.postsearch.model.SearchDocument;
import com.example.postsearch.model.SearchTermEntry;
import com.example.postsearch.repository.SearchDocumentRepository;
import com.example.postsearch.repository.SearchTermEntryRepository;

@Service
public class PostSearchService {

    private static final int MAX_CONTENT_LENGTH = 2000;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_TARGET_TYPE_LENGTH = 100;
    private static final int MAX_TARGET_ID_LENGTH = 200;
    private static final Pattern TARGET_TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]*");
    private static final Pattern TARGET_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]*");

    private final SearchDocumentRepository documents;
    private final SearchTermEntryRepository termEntries;
    private final Tokenizer tokenizer;

    public PostSearchService(
            SearchDocumentRepository documents,
            SearchTermEntryRepository termEntries,
            Tokenizer tokenizer) {
        this.documents = documents;
        this.termEntries = termEntries;
        this.tokenizer = tokenizer;
    }

    public record SearchPage(List<SearchDocument> items, String nextCursor) {
    }

    private record RecencyCursor(Instant createdAt, Long id) {
    }

    private record LikesCursor(int likeCount, Instant createdAt, Long id) {
    }

    private enum SortOrder {
        RECENCY("recency"),
        LIKES("likes");

        private final String apiValue;

        SortOrder(String apiValue) {
            this.apiValue = apiValue;
        }

        static SortOrder fromApiValue(String value) {
            if (value == null || value.isBlank() || RECENCY.apiValue.equals(value)) {
                return RECENCY;
            }
            if (LIKES.apiValue.equals(value)) {
                return LIKES;
            }
            throw new IllegalArgumentException("sort must be recency or likes");
        }
    }

    @Transactional
    public SearchDocument upsertDocument(
            String targetType,
            String targetId,
            String authorUsername,
            String content,
            Instant createdAt) {
        String type = requireTargetType(targetType);
        String id = requireTargetId(targetId);
        String author = requireText(authorUsername, "authorUsername");
        String body = requireText(content, "content");
        if (body.length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException("content must be 2000 characters or fewer");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt is required");
        }

        List<String> terms = tokenizer.tokenize(body);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("content must include at least one searchable term");
        }

        SearchDocument document = documents.findByTargetTypeAndTargetId(type, id)
                .map(existing -> {
                    existing.replaceSnapshot(author, body, createdAt);
                    return existing;
                })
                .orElseGet(() -> new SearchDocument(type, id, author, body, createdAt));

        SearchDocument saved = documents.saveAndFlush(document);
        termEntries.deleteByDocumentId(saved.getId());
        termEntries.saveAll(terms.stream()
                .map(term -> new SearchTermEntry(term, saved.getId()))
                .toList());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<SearchDocument> findByTarget(String targetType, String targetId) {
        return documents.findByTargetTypeAndTargetIdAndDeletedAtIsNull(
                requireTargetType(targetType), requireTargetId(targetId));
    }

    @Transactional
    public boolean applyPostSnapshot(String postId, String authorUsername, String content,
            Instant createdAt, long aggregateVersion) {
        return applyPostSnapshot(postId, null, authorUsername, content, createdAt, aggregateVersion);
    }

    @Transactional
    public boolean applyPostSnapshot(String postId, String authorUserId, String authorUsername, String content,
            Instant createdAt, long aggregateVersion) {
        SearchDocument existing = documents.findByTargetTypeAndTargetId("post", postId).orElse(null);
        if (existing != null && aggregateVersion <= existing.getAggregateVersion()) return false;
        SearchDocument document = upsertDocument("post", postId, authorUsername, content, createdAt);
        document.assignAuthorUserId(authorUserId);
        document.applyVersion(aggregateVersion);
        documents.save(document);
        return true;
    }

    @Transactional
    public boolean deletePostProjection(String postId, long aggregateVersion, Instant deletedAt) {
        SearchDocument document = documents.findByTargetTypeAndTargetId("post", postId).orElse(null);
        if (document == null || aggregateVersion <= document.getAggregateVersion()) return false;
        termEntries.deleteByDocumentId(document.getId());
        document.tombstone(aggregateVersion, deletedAt);
        documents.save(document);
        return true;
    }

    @Transactional(readOnly = true)
    public SearchPage search(String query, String sort, String cursor, int requestedPageSize) {
        List<String> terms = tokenizer.tokenize(query);
        if (terms.isEmpty()) {
            throw new IllegalArgumentException("q is required");
        }

        SortOrder sortOrder = SortOrder.fromApiValue(sort);
        int pageSize = clampPageSize(requestedPageSize);
        int fetchSize = pageSize + 1;

        List<SearchDocument> fetched;
        if (sortOrder == SortOrder.RECENCY) {
            if (cursor == null || cursor.isBlank()) {
                fetched = documents.searchRecencyFirst(terms, terms.size(), fetchSize);
            } else {
                RecencyCursor parsed = decodeRecencyCursor(cursor);
                fetched = documents.searchRecencyAfterCursor(terms, terms.size(), parsed.createdAt(), parsed.id(), fetchSize);
            }
        } else {
            if (cursor == null || cursor.isBlank()) {
                fetched = documents.searchLikesFirst(terms, terms.size(), fetchSize);
            } else {
                LikesCursor parsed = decodeLikesCursor(cursor);
                fetched = documents.searchLikesAfterCursor(
                        terms, terms.size(), parsed.likeCount(), parsed.createdAt(), parsed.id(), fetchSize);
            }
        }

        boolean hasMore = fetched.size() > pageSize;
        List<SearchDocument> pageItems = hasMore ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = hasMore ? encodeCursor(pageItems.get(pageItems.size() - 1), sortOrder) : null;
        return new SearchPage(pageItems, nextCursor);
    }

    @Transactional
    public SearchDocument updateLikeCount(String targetType, String targetId, Integer likeCount) {
        String type = requireTargetType(targetType);
        String id = requireTargetId(targetId);
        if (likeCount == null) {
            throw new IllegalArgumentException("likeCount is required");
        }
        if (likeCount < 0) {
            throw new IllegalArgumentException("likeCount must be zero or greater");
        }
        SearchDocument document = documents.findByTargetTypeAndTargetId(type, id)
                .orElseThrow(() -> new NotFoundException("search document not found"));
        document.updateLikeCount(likeCount);
        return documents.save(document);
    }

    private int clampPageSize(int requestedPageSize) {
        if (requestedPageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedPageSize, MAX_PAGE_SIZE);
    }

    private String encodeCursor(SearchDocument document, SortOrder sortOrder) {
        String raw;
        if (sortOrder == SortOrder.RECENCY) {
            raw = "recency|" + document.getCreatedAt() + "|" + document.getId();
        } else {
            raw = "likes|" + document.getLikeCount() + "|" + document.getCreatedAt() + "|" + document.getId();
        }
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private RecencyCursor decodeRecencyCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 3);
            if (parts.length != 3 || !"recency".equals(parts[0])) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new RecencyCursor(Instant.parse(parts[1]), Long.parseLong(parts[2]));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private LikesCursor decodeLikesCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 4);
            if (parts.length != 4 || !"likes".equals(parts[0])) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new LikesCursor(Integer.parseInt(parts[1]), Instant.parse(parts[2]), Long.parseLong(parts[3]));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private String requireTargetType(String value) {
        String trimmed = requireText(value, "targetType");
        if (trimmed.length() > MAX_TARGET_TYPE_LENGTH || !TARGET_TYPE.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("targetType must start with a letter and contain only letters, numbers, dots, underscores, or hyphens");
        }
        return trimmed;
    }

    private String requireTargetId(String value) {
        String trimmed = requireText(value, "targetId");
        if (trimmed.length() > MAX_TARGET_ID_LENGTH || !TARGET_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("targetId must contain only letters, numbers, dots, underscores, hyphens, or colons");
        }
        return trimmed;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
