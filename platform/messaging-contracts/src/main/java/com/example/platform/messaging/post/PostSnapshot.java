package com.example.platform.messaging.post;

import java.time.Instant;

public record PostSnapshot(String postId, String authorUserId, String authorUsername,
                           String content, String visibility, Instant createdAt, Instant updatedAt) {}
