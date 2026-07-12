package com.example.bff.service;

import java.time.Instant;

/** Minimal projections of the downstream service responses the composer consumes. */
public final class DownstreamViews {

    public record PostView(Long id, String authorUsername, String content, Instant createdAt,
            Instant updatedAt, Instant deletedAt, long version) {}

    public record CommentSummaryView(String targetType, String targetId, long commentCount) {}

    public record MediaSummaryView(String targetType, String targetId, long mediaCount) {}

    private DownstreamViews() {}
}
