package com.example.platform.messaging.media;
import java.time.Instant;
public record MediaProcessingCompleted(String mediaId, String format, Integer width, Integer height,
        Double durationSeconds, Instant completedAt) {}
