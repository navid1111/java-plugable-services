package com.example.platform.messaging.media;
import java.time.Instant;
public record MediaUploaded(String mediaId, String ownerUserId, String targetType, String targetId,
        String contentType, long bytes, String url, Instant uploadedAt) {}
