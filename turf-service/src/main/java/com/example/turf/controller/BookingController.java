package com.example.turf.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.turf.security.JwtHelper;
import com.example.turf.service.BookingService;

@RestController
@RequestMapping("/bookings")
public class BookingController {

    private final BookingService bookings;
    private final JwtHelper jwtHelper;

    public BookingController(BookingService bookings, JwtHelper jwtHelper) {
        this.bookings = bookings;
        this.jwtHelper = jwtHelper;
    }

    public record CreateBookingRequest(Long slotId) {
    }

    @GetMapping("/venues")
    public ResponseEntity<?> venues(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            jwtHelper.extractUsername(authorization);
            return ResponseEntity.ok(bookings.listVenues());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> book(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody(required = false) CreateBookingRequest request) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            Long slotId = request == null ? null : request.slotId();
            return ResponseEntity.status(HttpStatus.CREATED).body(bookings.book(username, slotId));
        } catch (BookingService.ConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (BookingService.NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/mine")
    public ResponseEntity<?> mine(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            return ResponseEntity.ok(bookings.mine(username));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> cancel(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        try {
            String username = jwtHelper.extractUsername(authorization);
            bookings.cancel(username, id);
            return ResponseEntity.noContent().build();
        } catch (BookingService.ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (BookingService.NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
