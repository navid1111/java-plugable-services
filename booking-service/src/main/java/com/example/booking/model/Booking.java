package com.example.booking.model;

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
        name = "bookings",
        indexes = {
                @Index(name = "idx_bookings_username", columnList = "username"),
                @Index(name = "idx_bookings_slot_status", columnList = "slot_id, status")
        })
public class Booking {

    public static final String ACTIVE = "active";
    public static final String CANCELLED = "cancelled";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "slot_id", nullable = false)
    private Long slotId;

    @Column(nullable = false, length = 100)
    private String username;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected Booking() {
        // required by JPA
    }

    public Booking(Long slotId, String username) {
        this.slotId = slotId;
        this.username = username;
        this.status = ACTIVE;
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

    public Long getSlotId() {
        return slotId;
    }

    public String getUsername() {
        return username;
    }

    public String getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public boolean isActive() {
        return ACTIVE.equals(status);
    }

    public void cancel() {
        if (isActive()) {
            status = CANCELLED;
            cancelledAt = Instant.now();
        }
    }
}
