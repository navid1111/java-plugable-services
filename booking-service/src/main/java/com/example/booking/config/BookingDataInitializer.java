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
        migrateLegacyVenueSchema();
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uk_bookings_active_slot
                ON bookings (slot_id)
                WHERE status = 'active'
                """);

        List<Resource> bookableResources = resources.findAllByOrderByNameAscIdAsc();
        if (bookableResources.isEmpty()) {
            // Seed a deliberately mixed set of resource types: this service books
            // anything with time slots (rooms, courts, appointments, ...).
            bookableResources = List.of(
                    resources.save(new Resource("Meeting Room A", "HQ, 3rd Floor")),
                    resources.save(new Resource("Tennis Court 1", "City Sports Club")),
                    resources.save(new Resource("Consultation Room 2", "Downtown Clinic")));
        }

        // Development volumes are long-lived. Always ensure every resource has
        // future inventory instead of leaving only yesterday's seeded slots.
        Instant base = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
        List<Slot> seededSlots = new ArrayList<>();
        for (int index = 0; index < bookableResources.size(); index++) {
            Resource resource = bookableResources.get(index);
            if (!slots.existsByResourceIdAndStartTimeAfter(resource.getId(), Instant.now())) {
                seededSlots.addAll(slotsFor(resource.getId(), base.plus(index, ChronoUnit.DAYS)));
            }
        }
        slots.saveAll(seededSlots);
    }

    /**
     * The service was generalized from venues to resources. Existing development
     * volumes still have slots.venue_id, which Hibernate cannot safely infer is a
     * rename. Make that migration idempotent before repositories seed/read data.
     */
    private void migrateLegacyVenueSchema() {
        jdbcTemplate.execute("""
                DO $$
                BEGIN
                  IF EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'slots' AND column_name = 'venue_id'
                  ) AND NOT EXISTS (
                    SELECT 1 FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = 'slots' AND column_name = 'resource_id'
                  ) THEN
                    ALTER TABLE slots RENAME COLUMN venue_id TO resource_id;
                  END IF;

                  IF to_regclass('public.venues') IS NOT NULL
                     AND to_regclass('public.resources') IS NOT NULL THEN
                    INSERT INTO resources (id, name, location, created_at)
                    SELECT id, name, location, created_at FROM venues
                    ON CONFLICT (id) DO NOTHING;
                  END IF;
                END $$
                """);
        jdbcTemplate.execute("DROP INDEX IF EXISTS idx_slots_venue_start");
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_slots_resource_start
                ON slots (resource_id, start_time)
                """);
        jdbcTemplate.execute("""
                SELECT setval(
                  pg_get_serial_sequence('resources', 'id'),
                  GREATEST(COALESCE((SELECT MAX(id) FROM resources), 1), 1),
                  EXISTS (SELECT 1 FROM resources)
                )
                """);
    }

    private List<Slot> slotsFor(Long resourceId, Instant dayStart) {
        return List.of(
                new Slot(resourceId, dayStart.plus(8, ChronoUnit.HOURS), dayStart.plus(9, ChronoUnit.HOURS)),
                new Slot(resourceId, dayStart.plus(10, ChronoUnit.HOURS), dayStart.plus(11, ChronoUnit.HOURS)),
                new Slot(resourceId, dayStart.plus(18, ChronoUnit.HOURS), dayStart.plus(19, ChronoUnit.HOURS)));
    }
}
