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

@Entity
@Table(
        name = "messages",
        indexes = {
                @Index(name = "idx_messages_chat_created_at", columnList = "chat_id, created_at DESC"),
                @Index(name = "idx_messages_created_at_id", columnList = "created_at DESC, id DESC")
        })
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @Column(name = "sender_username", nullable = false, length = 100)
    private String senderUsername;

    @Column(name = "sender_user_id", length = 36)
    private String senderUserId;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Message() {
        // required by JPA
    }

    public Message(Long chatId, String senderUserId, String senderUsername, String content) {
        this.chatId = chatId;
        this.senderUserId = senderUserId;
        this.senderUsername = senderUsername;
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

    public Long getChatId() {
        return chatId;
    }

    public String getSenderUsername() {
        return senderUsername;
    }
    public String getSenderUserId() { return senderUserId; }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
