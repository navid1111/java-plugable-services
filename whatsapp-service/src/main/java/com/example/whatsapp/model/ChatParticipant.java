package com.example.whatsapp.model;

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
        name = "chat_participants",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_chat_participants_chat_user_id",
                columnNames = {"chat_id", "user_id"}),
        indexes = {
                @Index(name = "idx_chat_participants_user_id", columnList = "user_id"),
                @Index(name = "idx_chat_participants_chat", columnList = "chat_id")
        })
public class ChatParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(name = "user_id", length = 36)
    private String userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ChatParticipant() {
        // required by JPA
    }

    public ChatParticipant(Long chatId, String userId, String username) {
        this.chatId = chatId;
        this.userId = userId;
        this.username = username;
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

    public Long getChatId() {
        return chatId;
    }

    public String getUsername() {
        return username;
    }
    public String getUserId() { return userId; }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
