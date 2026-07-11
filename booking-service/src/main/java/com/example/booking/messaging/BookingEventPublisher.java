package com.example.booking.messaging;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.example.booking.model.Booking;
import com.example.booking.model.Slot;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.support.OutboxMessage;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;

/**
 * Writes booking domain events to the shared outbox. Every method must be invoked inside the
 * same transaction as the booking state change it describes: the event is then committed
 * atomically with the booking (or not at all on rollback), so a broker outage cannot cost a
 * committed fact and a committed booking always emits exactly one created event. Slot locking
 * stays entirely local to the booking transaction.
 */
@Component
public class BookingEventPublisher {

    private static final String PRODUCER = "booking-service";

    private final OutboxMessageRepository outbox;
    private final SafeEventSerializer serializer;

    public BookingEventPublisher(OutboxMessageRepository outbox, SafeEventSerializer serializer) {
        this.outbox = outbox;
        this.serializer = serializer;
    }

    public void bookingCreated(Booking booking, Slot slot, Long resourceId) {
        emit(BookingEventTypes.BOOKING_CREATED_V1, "booking", booking.getId(), 1,
                new BookingEventPayloads.BookingCreated(booking.getId(), slot.getId(), resourceId,
                        booking.getUsername(), slot.getStartTime(), slot.getEndTime(), booking.getCreatedAt()));
        slotBecameUnavailable(slot.getId(), resourceId, booking.getId());
    }

    public void bookingCancelled(Booking booking, Long resourceId) {
        emit(BookingEventTypes.BOOKING_CANCELLED_V1, "booking", booking.getId(), 2,
                new BookingEventPayloads.BookingCancelled(booking.getId(), booking.getSlotId(),
                        booking.getUsername(), booking.getCancelledAt()));
        slotBecameAvailable(booking.getSlotId(), resourceId, booking.getId());
    }

    private void slotBecameUnavailable(Long slotId, Long resourceId, long version) {
        emit(BookingEventTypes.SLOT_AVAILABILITY_CHANGED_V1, "slot", slotId, version,
                new BookingEventPayloads.SlotAvailabilityChanged(slotId, resourceId, false, Instant.now()));
    }

    private void slotBecameAvailable(Long slotId, Long resourceId, long version) {
        emit(BookingEventTypes.SLOT_AVAILABILITY_CHANGED_V1, "slot", slotId, version,
                new BookingEventPayloads.SlotAvailabilityChanged(slotId, resourceId, true, Instant.now()));
    }

    private void emit(String eventType, String aggregateType, Long aggregateId, long aggregateVersion,
            Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.fact(eventType, 1, PRODUCER,
                aggregateType, String.valueOf(aggregateId), aggregateVersion, null, null, null, payload);
        outbox.save(new OutboxMessage(envelope.eventId(), aggregateType, String.valueOf(aggregateId),
                eventType, 1, serializer.serialize(envelope), Instant.now()));
    }
}
