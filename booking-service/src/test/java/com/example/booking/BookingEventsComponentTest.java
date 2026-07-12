package com.example.booking;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.booking.model.Resource;
import com.example.booking.model.Slot;
import com.example.booking.repository.ResourceRepository;
import com.example.booking.repository.SlotRepository;
import com.example.booking.service.BookingService;

/**
 * Proves T045: booking emits its domain events through the outbox in the same transaction as
 * the booking, so concurrent booking of one slot still yields exactly one booking (local slot
 * lock intact) and exactly one created event (idempotent notification), and cancellation is
 * idempotent. The relay is disabled so this asserts committed outbox facts without a broker.
 */
@SpringBootTest(properties = "booking.messaging.relay-enabled=false")
@Testcontainers
class BookingEventsComponentTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired BookingService bookingService;
    @Autowired ResourceRepository resources;
    @Autowired SlotRepository slots;
    @Autowired JdbcTemplate jdbc;

    private Long slotId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate table bookings restart identity");
        jdbc.execute("truncate table outbox_messages");
        jdbc.execute("truncate table slots restart identity cascade");
        jdbc.execute("truncate table resources restart identity cascade");
        Resource resource = resources.save(new Resource("Court 1", "Club"));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        slotId = slots.save(new Slot(resource.getId(), start, start.plus(1, ChronoUnit.HOURS))).getId();
    }

    private long count(String eventType) {
        return jdbc.queryForObject(
                "select count(*) from outbox_messages where event_type = ?", Long.class, eventType);
    }

    @Test
    void concurrentBookingYieldsOneBookingAndOneCreatedEvent() throws Exception {
        int contenders = 8;
        CyclicBarrier barrier = new CyclicBarrier(contenders);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        for (int i = 0; i < contenders; i++) {
            String user = "user-" + i;
            pool.submit(() -> {
                try {
                    barrier.await();
                    bookingService.book(java.util.UUID.nameUUIDFromBytes(user.getBytes()).toString(), user, slotId);
                    succeeded.incrementAndGet();
                } catch (BookingService.ConflictException e) {
                    conflicted.incrementAndGet();
                } catch (Exception ignored) {
                    // Treated as a non-success; assertions below cover the outcome.
                }
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, succeeded.get(), "exactly one booking wins the slot (no double booking)");
        assertEquals(contenders - 1, conflicted.get(), "everyone else is rejected");
        assertEquals(1, count("booking.created.v1"), "exactly one created event per committed booking");
        assertEquals(1, count("slot.availability-changed.v1"), "one availability change on booking");
        // Losers rolled back, so no orphan events were emitted.
        assertEquals(2, jdbc.queryForObject("select count(*) from outbox_messages", Long.class));
    }

    @Test
    void cancellationIsIdempotent() {
        String aliceId = "550e8400-e29b-41d4-a716-446655440000";
        var view = bookingService.book(aliceId, "alice", slotId);
        assertEquals(1, count("booking.created.v1"));
        assertEquals(1, bookingService.mine(aliceId).size(), "rename does not affect ownership lookup");

        bookingService.cancel(aliceId, view.id());
        bookingService.cancel(aliceId, view.id()); // repeat: must not emit again

        assertEquals(1, count("booking.cancelled.v1"), "cancel emits once despite being repeated");
        assertEquals(2, count("slot.availability-changed.v1"), "unavailable on book, available on cancel");
    }
}
