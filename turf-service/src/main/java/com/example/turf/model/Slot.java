package com.example.turf.model;

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
        name = "slots",
        indexes = {
                @Index(name = "idx_slots_venue_start", columnList = "venue_id, start_time"),
                @Index(name = "idx_slots_start_time", columnList = "start_time")
        })
public class Slot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Slot() {
        // required by JPA
    }

    public Slot(Long venueId, Instant startTime, Instant endTime) {
        this.venueId = venueId;
        this.startTime = startTime;
        this.endTime = endTime;
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

    public Long getVenueId() {
        return venueId;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
