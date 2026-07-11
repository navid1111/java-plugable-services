package com.example.platform.messaging.post;

import java.time.Instant;

public record FollowChanged(String followerUserId, String followeeUserId, Instant changedAt) {}
