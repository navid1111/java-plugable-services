package com.example.platform.messaging.post;

import java.time.Instant;

public record PostDeleted(String postId, String deletedByUserId, Instant deletedAt) {}
