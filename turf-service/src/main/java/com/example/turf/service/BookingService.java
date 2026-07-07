package com.example.turf.service;

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

import com.example.turf.model.Booking;
import com.example.turf.model.Slot;
import com.example.turf.model.Venue;
import com.example.turf.repository.BookingRepository;
import com.example.turf.repository.SlotRepository;
import com.example.turf.repository.VenueRepository;

@Service
public class BookingService {

    private final VenueRepository venues;
    private final SlotRepository slots;
    private final BookingRepository bookings;

    public BookingService(VenueRepository venues, SlotRepository slots, BookingRepository bookings) {
        this.venues = venues;
        this.slots = slots;
        this.bookings = bookings;
    }

    public record VenueView(Long id, String name, String location, List<SlotView> slots) {
    }

    public record SlotView(Long id, Instant startTime, Instant endTime, boolean available) {
    }

    public record BookingView(
            Long id,
            Long slotId,
            Long venueId,
            String venueName,
            String venueLocation,
            String username,
            String status,
            Instant startTime,
            Instant endTime,
            Instant createdAt,
            Instant cancelledAt) {
    }

    @Transactional(readOnly = true)
    public List<VenueView> listVenues() {
        List<Venue> allVenues = venues.findAllByOrderByNameAscIdAsc();
        if (allVenues.isEmpty()) {
            return List.of();
        }

        List<Long> venueIds = allVenues.stream().map(Venue::getId).toList();
        List<Slot> allSlots = slots.findByVenueIdInOrderByStartTimeAscIdAsc(venueIds);
        Set<Long> unavailableSlotIds = activeSlotIds(allSlots.stream().map(Slot::getId).toList());
        Map<Long, List<Slot>> slotsByVenue = allSlots.stream()
                .collect(Collectors.groupingBy(Slot::getVenueId));

        return allVenues.stream()
                .map(venue -> new VenueView(
                        venue.getId(),
                        venue.getName(),
                        venue.getLocation(),
                        slotsByVenue.getOrDefault(venue.getId(), List.of()).stream()
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
            Venue venue = venues.findById(slot.getVenueId())
                    .orElseThrow(() -> new NotFoundException("venue not found"));
            return toView(booking, slot, venue);
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
        booking.cancel();
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
        Map<Long, Venue> venuesById = venues.findAllById(slotsById.values().stream()
                        .map(Slot::getVenueId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(Venue::getId, Function.identity()));

        return bookingRows.stream()
                .map(booking -> {
                    Slot slot = slotsById.get(booking.getSlotId());
                    Venue venue = slot == null ? null : venuesById.get(slot.getVenueId());
                    return toView(booking, slot, venue);
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

    private BookingView toView(Booking booking, Slot slot, Venue venue) {
        return new BookingView(
                booking.getId(),
                booking.getSlotId(),
                slot == null ? null : slot.getVenueId(),
                venue == null ? null : venue.getName(),
                venue == null ? null : venue.getLocation(),
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
