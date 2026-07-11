package com.example.booking.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.booking.model.Booking;
import com.example.booking.model.Slot;
import com.example.booking.model.Resource;
import com.example.booking.repository.BookingRepository;
import com.example.booking.repository.SlotRepository;
import com.example.booking.repository.ResourceRepository;

@Service
public class BookingService {

    private final ResourceRepository resources;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final com.example.booking.messaging.BookingEventPublisher events;

    public BookingService(ResourceRepository resources, SlotRepository slots, BookingRepository bookings,
            com.example.booking.messaging.BookingEventPublisher events) {
        this.resources = resources;
        this.slots = slots;
        this.bookings = bookings;
        this.events = events;
    }

    public record ResourceView(Long id, String name, String location, List<SlotView> slots) {
    }

    public record SlotView(Long id, Instant startTime, Instant endTime, boolean available) {
    }

    public record BookingView(
            Long id,
            Long slotId,
            Long resourceId,
            String resourceName,
            String resourceLocation,
            String username,
            String status,
            Instant startTime,
            Instant endTime,
            Instant createdAt,
            Instant cancelledAt) {
    }

    @Transactional(readOnly = true)
    public List<ResourceView> listResources() {
        List<Resource> allResources = resources.findAllByOrderByNameAscIdAsc();
        if (allResources.isEmpty()) {
            return List.of();
        }

        List<Long> resourceIds = allResources.stream().map(Resource::getId).toList();
        List<Slot> allSlots = slots.findByResourceIdInOrderByStartTimeAscIdAsc(resourceIds);
        Set<Long> unavailableSlotIds = activeSlotIds(allSlots.stream().map(Slot::getId).toList());
        Map<Long, List<Slot>> slotsByResource = allSlots.stream()
                .collect(Collectors.groupingBy(Slot::getResourceId));

        return allResources.stream()
                .map(resource -> new ResourceView(
                        resource.getId(),
                        resource.getName(),
                        resource.getLocation(),
                        slotsByResource.getOrDefault(resource.getId(), List.of()).stream()
                                .map(slot -> new SlotView(
                                        slot.getId(),
                                        slot.getStartTime(),
                                        slot.getEndTime(),
                                        !unavailableSlotIds.contains(slot.getId())))
                                .toList()))
                .toList();
    }

    @Transactional
    public BookingView book(String username, Long slotId) {
        String currentUser = requireText(username, "username");
        if (slotId == null) {
            throw new IllegalArgumentException("slotId is required");
        }

        Slot slot = slots.findById(slotId)
                .orElseThrow(() -> new NotFoundException("slot not found"));
        if (!slot.getStartTime().isAfter(Instant.now())) {
            throw new IllegalArgumentException("slot is in the past");
        }

        try {
            Booking booking = bookings.saveAndFlush(new Booking(slot.getId(), currentUser));
            Resource resource = resources.findById(slot.getResourceId())
                    .orElseThrow(() -> new NotFoundException("resource not found"));
            // Same transaction as the booking: the event commits atomically or not at all.
            events.bookingCreated(booking, slot, resource.getId());
            return toView(booking, slot, resource);
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("slot already booked");
        }
    }

    @Transactional(readOnly = true)
    public List<BookingView> mine(String username) {
        String currentUser = requireText(username, "username");
        List<Booking> mine = bookings.findByUsernameOrderByCreatedAtDescIdDesc(currentUser);
        return enrich(mine);
    }

    @Transactional
    public void cancel(String username, Long bookingId) {
        String currentUser = requireText(username, "username");
        if (bookingId == null) {
            throw new NotFoundException("booking not found");
        }

        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> new NotFoundException("booking not found"));
        if (!booking.getUsername().equals(currentUser)) {
            throw new ForbiddenException("cannot cancel another user's booking");
        }
        // Emit only on the active -> cancelled transition, so repeated cancels stay idempotent.
        boolean wasActive = booking.isActive();
        booking.cancel();
        if (wasActive) {
            Long resourceId = slots.findById(booking.getSlotId())
                    .map(Slot::getResourceId)
                    .orElse(null);
            events.bookingCancelled(booking, resourceId);
        }
    }

    private List<BookingView> enrich(List<Booking> bookingRows) {
        if (bookingRows.isEmpty()) {
            return List.of();
        }

        Map<Long, Slot> slotsById = slots.findAllById(bookingRows.stream()
                        .map(Booking::getSlotId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(Slot::getId, Function.identity()));
        Map<Long, Resource> resourcesById = resources.findAllById(slotsById.values().stream()
                        .map(Slot::getResourceId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(Resource::getId, Function.identity()));

        return bookingRows.stream()
                .map(booking -> {
                    Slot slot = slotsById.get(booking.getSlotId());
                    Resource resource = slot == null ? null : resourcesById.get(slot.getResourceId());
                    return toView(booking, slot, resource);
                })
                .toList();
    }

    private Set<Long> activeSlotIds(Collection<Long> slotIds) {
        if (slotIds.isEmpty()) {
            return Set.of();
        }
        return bookings.findByStatusAndSlotIdIn(Booking.ACTIVE, slotIds).stream()
                .map(Booking::getSlotId)
                .collect(Collectors.toSet());
    }

    private BookingView toView(Booking booking, Slot slot, Resource resource) {
        return new BookingView(
                booking.getId(),
                booking.getSlotId(),
                slot == null ? null : slot.getResourceId(),
                resource == null ? null : resource.getName(),
                resource == null ? null : resource.getLocation(),
                booking.getUsername(),
                booking.getStatus(),
                slot == null ? null : slot.getStartTime(),
                slot == null ? null : slot.getEndTime(),
                booking.getCreatedAt(),
                booking.getCancelledAt());
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
