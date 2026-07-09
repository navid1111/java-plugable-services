package com.example.booking.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.booking.model.Slot;
import com.example.booking.model.Resource;
import com.example.booking.repository.SlotRepository;
import com.example.booking.repository.ResourceRepository;

@Component
public class BookingDataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final ResourceRepository resources;
    private final SlotRepository slots;

    public BookingDataInitializer(JdbcTemplate jdbcTemplate, ResourceRepository resources, SlotRepository slots) {
        this.jdbcTemplate = jdbcTemplate;
        this.resources = resources;
        this.slots = slots;
    }

    @Override
    @Transactional
    public void run(String... args) {
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_bookings_active_slot
                ON bookings (slot_id)
                WHERE status = 'active'
                """);

        if (resources.count() > 0) {
            return;
        }

        // Seed a deliberately mixed set of resource types: this service books
        // anything with time slots (rooms, courts, appointments, ...).
        Resource room = resources.save(new Resource("Meeting Room A", "HQ, 3rd Floor"));
        Resource court = resources.save(new Resource("Tennis Court 1", "City Sports Club"));
        Resource clinic = resources.save(new Resource("Consultation Room 2", "Downtown Clinic"));

        Instant base = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        List<Slot> seededSlots = new ArrayList<>();
        seededSlots.addAll(slotsFor(room.getId(), base));
        seededSlots.addAll(slotsFor(court.getId(), base.plus(1, ChronoUnit.DAYS)));
        seededSlots.addAll(slotsFor(clinic.getId(), base.plus(2, ChronoUnit.DAYS)));
        slots.saveAll(seededSlots);
    }

    private List<Slot> slotsFor(Long resourceId, Instant dayStart) {
        return List.of(
                new Slot(resourceId, dayStart.plus(8, ChronoUnit.HOURS), dayStart.plus(9, ChronoUnit.HOURS)),
                new Slot(resourceId, dayStart.plus(10, ChronoUnit.HOURS), dayStart.plus(11, ChronoUnit.HOURS)),
                new Slot(resourceId, dayStart.plus(18, ChronoUnit.HOURS), dayStart.plus(19, ChronoUnit.HOURS)));
    }
}
