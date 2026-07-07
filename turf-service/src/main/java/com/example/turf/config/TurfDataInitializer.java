package com.example.turf.config;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.turf.model.Slot;
import com.example.turf.model.Venue;
import com.example.turf.repository.SlotRepository;
import com.example.turf.repository.VenueRepository;

@Component
public class TurfDataInitializer implements CommandLineRunner {

    private final JdbcTemplate jdbcTemplate;
    private final VenueRepository venues;
    private final SlotRepository slots;

    public TurfDataInitializer(JdbcTemplate jdbcTemplate, VenueRepository venues, SlotRepository slots) {
        this.jdbcTemplate = jdbcTemplate;
        this.venues = venues;
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

        if (venues.count() > 0) {
            return;
        }

        Venue north = venues.save(new Venue("Northside Arena", "Mirpur DOHS"));
        Venue city = venues.save(new Venue("City Five Turf", "Banani"));
        Venue river = venues.save(new Venue("Riverside Sports Ground", "Bashundhara"));

        Instant base = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        List<Slot> seededSlots = new ArrayList<>();
        seededSlots.addAll(slotsFor(north.getId(), base));
        seededSlots.addAll(slotsFor(city.getId(), base.plus(1, ChronoUnit.DAYS)));
        seededSlots.addAll(slotsFor(river.getId(), base.plus(2, ChronoUnit.DAYS)));
        slots.saveAll(seededSlots);
    }

    private List<Slot> slotsFor(Long venueId, Instant dayStart) {
        return List.of(
                new Slot(venueId, dayStart.plus(8, ChronoUnit.HOURS), dayStart.plus(9, ChronoUnit.HOURS)),
                new Slot(venueId, dayStart.plus(10, ChronoUnit.HOURS), dayStart.plus(11, ChronoUnit.HOURS)),
                new Slot(venueId, dayStart.plus(18, ChronoUnit.HOURS), dayStart.plus(19, ChronoUnit.HOURS)));
    }
}
