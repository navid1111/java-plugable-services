package com.example.booking.messaging;

import java.time.Instant;

/** Payload shapes for booking-owned events. Restricted booking metadata only — no PII beyond username. */
public final class BookingEventPayloads {

    public record BookingCreated(Long bookingId, Long slotId, Long resourceId, String username,
            Instant startTime, Instant endTime, Instant createdAt) {
    }

    public record BookingCancelled(Long bookingId, Long slotId, String username, Instant cancelledAt) {
    }

    public record SlotAvailabilityChanged(Long slotId, Long resourceId, boolean available, Instant changedAt) {
    }

    private BookingEventPayloads() {
    }
}
