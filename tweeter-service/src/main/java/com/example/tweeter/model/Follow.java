package com.example.tweeter.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "follows",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_follows_follower_followee",
                columnNames = {"follower_user_id", "followee_user_id"}),
        indexes = {
                @Index(name = "idx_follows_follower", columnList = "follower_user_id"),
                @Index(name = "idx_follows_followee", columnList = "followee_user_id")
        })
public class Follow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "follower_username", nullable = false, length = 100)
    private String followerUsername;

    @Column(name = "follower_user_id")
    private String followerUserId;

    @Column(name = "followee_username", nullable = false, length = 100)
    private String followeeUsername;

    @Column(name = "followee_user_id")
    private String followeeUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Follow() {
        // required by JPA
    }

    public Follow(String followerUsername, String followeeUsername) {
        this.followerUsername = followerUsername;
        this.followeeUsername = followeeUsername;
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

    public String getFollowerUsername() {
        return followerUsername;
    }
    public String getFollowerUserId() { return followerUserId; }
    public String getFolloweeUserId() { return followeeUserId; }

    public String getFolloweeUsername() {
        return followeeUsername;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
