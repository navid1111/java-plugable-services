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
        name = "inbox_entries",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inbox_entries_message_recipient",
                columnNames = {"message_id", "recipient_username"}),
        indexes = {
                @Index(name = "idx_inbox_recipient_delivered", columnList = "recipient_username, delivered"),
                @Index(name = "idx_inbox_created_at", columnList = "created_at")
        })
public class InboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "recipient_username", nullable = false, length = 100)
    private String recipientUsername;

    @Column(nullable = false)
    private boolean delivered;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    protected InboxEntry() {
        // required by JPA
    }

    public InboxEntry(Long messageId, String recipientUsername) {
        this.messageId = messageId;
        this.recipientUsername = recipientUsername;
        this.delivered = false;
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

    public Long getMessageId() {
        return messageId;
    }

    public String getRecipientUsername() {
        return recipientUsername;
    }

    public boolean isDelivered() {
        return delivered;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public void markDelivered() {
        delivered = true;
        deliveredAt = Instant.now();
    }
}
