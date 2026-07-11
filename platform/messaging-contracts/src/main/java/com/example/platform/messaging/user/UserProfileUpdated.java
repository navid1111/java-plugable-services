package com.example.platform.messaging.user;
import java.time.Instant;
public record UserProfileUpdated(String userId, String username, Instant updatedAt) {}
