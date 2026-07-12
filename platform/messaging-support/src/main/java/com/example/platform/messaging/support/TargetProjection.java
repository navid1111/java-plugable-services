package com.example.platform.messaging.support;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "target_projections")
@IdClass(TargetProjection.Key.class)
public class TargetProjection {
    @Id private String targetType;
    @Id private String targetId;
    private String ownerUserId;
    private String ownerUsername;
    private long aggregateVersion;
    private boolean active;
    private Instant updatedAt;

    protected TargetProjection() {}
    public TargetProjection(String type, String id, String ownerUserId, String ownerUsername,
            long version, boolean active, Instant updatedAt) {
        this.targetType = type; this.targetId = id; this.ownerUserId = ownerUserId;
        this.ownerUsername = ownerUsername;
        this.aggregateVersion = version; this.active = active; this.updatedAt = updatedAt;
    }
    public boolean apply(String ownerUserId, String ownerUsername, long version, boolean active, Instant when) {
        if (version <= aggregateVersion) return false;
        this.ownerUserId = ownerUserId == null ? this.ownerUserId : ownerUserId;
        this.ownerUsername = ownerUsername == null ? this.ownerUsername : ownerUsername;
        this.aggregateVersion = version; this.active = active; this.updatedAt = when; return true;
    }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getOwnerUsername() { return ownerUsername; }
    public long getAggregateVersion() { return aggregateVersion; }
    public boolean isActive() { return active; }

    public static class Key implements Serializable {
        private String targetType; private String targetId;
        public Key() {}
        public Key(String type, String id) { targetType = type; targetId = id; }
        @Override public boolean equals(Object o) { return o instanceof Key k
                && Objects.equals(targetType, k.targetType) && Objects.equals(targetId, k.targetId); }
        @Override public int hashCode() { return Objects.hash(targetType, targetId); }
    }
}
