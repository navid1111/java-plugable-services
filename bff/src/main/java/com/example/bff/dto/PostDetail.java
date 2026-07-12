package com.example.bff.dto;

import java.time.Instant;
import java.util.List;

/**
 * Client-shaped post detail composed from multiple owning services. {@code comments} and
 * {@code media} are nullable: when an optional dependency is unavailable within the deadline,
 * that section is {@code null} and its name appears in {@code degraded} (partial response).
 */
public record PostDetail(
        PostSection post,
        AuthorSection author,
        CommentSummary comments,
        MediaSummary media,
        List<String> degraded) {

    public record PostSection(long id, String content, Instant createdAt, Instant updatedAt, long version) {}

    public record AuthorSection(String username) {}

    public record CommentSummary(long commentCount) {}

    public record MediaSummary(long mediaCount) {}
}
