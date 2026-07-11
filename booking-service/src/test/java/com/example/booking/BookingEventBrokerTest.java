package com.example.booking;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.example.booking.model.Resource;
import com.example.booking.model.Slot;
import com.example.booking.repository.ResourceRepository;
import com.example.booking.repository.SlotRepository;
import com.example.booking.service.BookingService;

/**
 * End-to-end outbox flow for booking against real PostgreSQL and RabbitMQ (T049 coverage for
 * the booking flow): with the relay enabled, a committed booking's outbox row is drained and
 * the {@code booking.created.v1} event is published — confirmed, persistent — to the shared
 * exchange and delivered to a bound queue.
 */
@SpringBootTest(properties = "booking.messaging.outbox.delay-ms=200")
@Testcontainers
class BookingEventBrokerTest {

    private static final String EXCHANGE = "platform.events.v1";
    private static final String PROBE_QUEUE = "booking-broker-test.probe";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Autowired BookingService bookingService;
    @Autowired ResourceRepository resources;
    @Autowired SlotRepository slots;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired RabbitTemplate rabbitTemplate;
    @Autowired JdbcTemplate jdbc;

    @Test
    void committedBookingIsPublishedToTheBroker() {
        // Bind a probe queue to both booking event routing keys. With mandatory routing an
        // unbound event would be treated as a publish failure, so every emitted event needs a
        // consumer (in production, an availability-projection consumer).
        Queue probe = new Queue(PROBE_QUEUE, false);
        TopicExchange events = new TopicExchange(EXCHANGE, true, false);
        rabbitAdmin.declareQueue(probe);
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(events).with("booking.created.v1"));
        rabbitAdmin.declareBinding(BindingBuilder.bind(probe).to(events).with("slot.availability-changed.v1"));

        Resource resource = resources.save(new Resource("Court 1", "Club"));
        Instant start = Instant.now().plus(1, ChronoUnit.DAYS);
        Long slotId = slots.save(new Slot(resource.getId(), start, start.plus(1, ChronoUnit.HOURS))).getId();

        var view = bookingService.book("alice", slotId);
        assertNotNull(view.id());

        // The relay drains the outbox (~200ms cadence) and confirms each publish. Read the
        // delivered messages and confirm the created event arrived with its envelope.
        boolean sawCreated = false;
        for (int i = 0; i < 5 && !sawCreated; i++) {
            Message m = rabbitTemplate.receive(PROBE_QUEUE, 10_000);
            assertNotNull(m, "expected an event delivered to the broker");
            String body = new String(m.getBody());
            if (body.contains("booking.created.v1") && body.contains("\"bookingId\":" + view.id())) {
                sawCreated = true;
            }
        }
        assertTrue(sawCreated, "booking.created.v1 for the new booking should reach the broker");

        // Confirmed-only completion: both outbox rows drain once published.
        long deadline = System.currentTimeMillis() + 5_000;
        long unpublished = Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            unpublished = jdbc.queryForObject(
                    "select count(*) from outbox_messages where published_at is null", Long.class);
            if (unpublished == 0) {
                break;
            }
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        assertEquals(0L, unpublished, "outbox drains after confirmed publish");
    }
}
