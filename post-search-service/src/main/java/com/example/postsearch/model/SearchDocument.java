package com.example.postsearch.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "search_documents",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_search_documents_target", columnNames = {"target_type", "target_id"})
        },
        indexes = {
                @Index(name = "idx_search_documents_target", columnList = "target_type, target_id"),
                @Index(name = "idx_search_documents_created_at", columnList = "created_at DESC, id DESC"),
                @Index(name = "idx_search_documents_likes", columnList = "like_count DESC, created_at DESC, id DESC")
        })
public class SearchDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 100)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 200)
    private String targetId;

    @Column(name = "author_username", nullable = false, length = 100)
    private String authorUsername;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "indexed_at", nullable = false)
    private Instant indexedAt;

    protected SearchDocument() {
        // required by JPA
    }

    public SearchDocument(String targetType, String targetId, String authorUsername, String content, Instant createdAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.authorUsername = authorUsername;
        this.content = content;
        this.createdAt = createdAt;
        this.likeCount = 0;
        this.indexedAt = Instant.now();
    }

    public void replaceSnapshot(String authorUsername, String content, Instant createdAt) {
        this.authorUsername = authorUsername;
        this.content = content;
        this.createdAt = createdAt;
        this.indexedAt = Instant.now();
    }

    public void updateLikeCount(int likeCount) {
        this.likeCount = likeCount;
        this.indexedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getAuthorUsername() {
        return authorUsername;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public int getLikeCount() {
        return likeCount;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }
}
