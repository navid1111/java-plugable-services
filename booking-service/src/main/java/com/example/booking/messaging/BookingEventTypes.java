package com.example.booking.messaging;

/**
 * Event types owned by booking-service, mirroring the platform event catalog. Kept local
 * to this service; the shared {@code EventTypes} registry stays owned by its producers.
 */
public final class BookingEventTypes {
    public static final String BOOKING_CREATED_V1 = "booking.created.v1";
    public static final String BOOKING_CANCELLED_V1 = "booking.cancelled.v1";
    public static final String SLOT_AVAILABILITY_CHANGED_V1 = "slot.availability-changed.v1";

    private BookingEventTypes() {
    }
}
