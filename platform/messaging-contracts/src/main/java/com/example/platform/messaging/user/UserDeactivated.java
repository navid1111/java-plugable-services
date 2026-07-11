package com.example.platform.messaging.user;
import java.time.Instant;
public record UserDeactivated(String userId, Instant deactivatedAt) {}
