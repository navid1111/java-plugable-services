package com.example.platform.messaging.user;
import java.time.Instant;
public record UserRegistered(String userId, String username, Instant registeredAt) {}
