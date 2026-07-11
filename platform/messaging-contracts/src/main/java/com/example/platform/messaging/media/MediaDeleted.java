package com.example.platform.messaging.media;
import java.time.Instant;
public record MediaDeleted(String mediaId, String targetType, String targetId, Instant deletedAt) {}
