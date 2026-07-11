package com.example.platform.messaging.target;

import java.util.Map;

/** Canonical ownership registry for entities referenced by comments and media. */
public final class TargetTypeRegistry {
    private static final Map<String, String> OWNERS = Map.of("post", "tweeter-service");
    private TargetTypeRegistry() {}
    public static String requireOwner(String targetType) {
        if (targetType == null || targetType.isBlank()) throw new IllegalArgumentException("targetType is required");
        String owner = OWNERS.get(targetType.trim().toLowerCase());
        if (owner == null) throw new IllegalArgumentException("unregistered targetType: " + targetType);
        return owner;
    }
    public static boolean isRegistered(String targetType) {
        return targetType != null && OWNERS.containsKey(targetType.trim().toLowerCase());
    }
    public static Map<String, String> owners() { return OWNERS; }
}
