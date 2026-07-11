package com.example.tweeter.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "posts",
        indexes = {
                @Index(name = "idx_posts_author_created_at", columnList = "author_username, created_at DESC"),
                @Index(name = "idx_posts_created_at_id", columnList = "created_at DESC, id DESC")
        })
public class Post {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "author_username", nullable = false, length = 100)
    private String authorUsername;

    @Column(nullable = false, length = 280)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected Post() {
        // required by JPA
    }

    public Post(String authorUsername, String content) {
        this.authorUsername = authorUsername;
        this.content = content;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() {
        return id;
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

    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getDeletedAt() { return deletedAt; }
    public long getVersion() { return version; }
    public boolean isDeleted() { return deletedAt != null; }

    public void updateContent(String content) {
        if (isDeleted()) throw new IllegalStateException("post is deleted");
        this.content = content;
    }

    public void delete(Instant when) {
        if (deletedAt == null) deletedAt = when;
    }
}
