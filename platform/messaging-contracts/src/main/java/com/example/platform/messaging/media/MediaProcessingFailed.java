package com.example.platform.messaging.media;
import java.time.Instant;
public record MediaProcessingFailed(String mediaIntentId, String reasonCode, Instant failedAt) {}
