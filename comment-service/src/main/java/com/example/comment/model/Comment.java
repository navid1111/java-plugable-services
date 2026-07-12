package com.example.comment.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(
        name = "comments",
        indexes = {
                @Index(name = "idx_comments_target_created_at", columnList = "target_type, target_id, created_at DESC, id DESC"),
                @Index(name = "idx_comments_author_created_at", columnList = "author_user_id, created_at DESC, id DESC")
        })
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 100)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 200)
    private String targetId;

    @Column(name = "author_username", nullable = false, length = 100)
    private String authorUsername;

    @Column(name = "author_user_id")
    private String authorUserId;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Comment() {
        // required by JPA
    }

    public Comment(String targetType, String targetId, String authorUserId, String authorUsername, String content) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.authorUserId = authorUserId;
        this.authorUsername = authorUsername;
        this.content = content;
    }
    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
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
    public String getAuthorUserId() { return authorUserId; }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
