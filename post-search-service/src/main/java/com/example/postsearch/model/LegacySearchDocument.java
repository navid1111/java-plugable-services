package com.example.postsearch.model;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "legacy_search_documents")
@IdClass(LegacySearchDocument.Key.class)
public class LegacySearchDocument {
    @Id private String targetType;
    @Id private String targetId;
    private String authorUsername;
    private String content;
    private Instant createdAt;
    private Instant observedAt;

    protected LegacySearchDocument() {}
    public LegacySearchDocument(String targetType, String targetId, String authorUsername,
            String content, Instant createdAt) {
        this.targetType = targetType; this.targetId = targetId; this.authorUsername = authorUsername;
        this.content = content; this.createdAt = createdAt; this.observedAt = Instant.now();
    }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getAuthorUsername() { return authorUsername; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }

    public static class Key implements Serializable {
        private String targetType; private String targetId;
        public Key() {}
        public Key(String targetType, String targetId) { this.targetType = targetType; this.targetId = targetId; }
        @Override public boolean equals(Object o) { return o instanceof Key k
                && Objects.equals(targetType, k.targetType) && Objects.equals(targetId, k.targetId); }
        @Override public int hashCode() { return Objects.hash(targetType, targetId); }
    }
}
