package com.example.demo.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.JwtService;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.support.TransactionalEventWriter;
import com.example.platform.messaging.user.*;
import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * Public authentication endpoints. These live under /auth, which Kong leaves
 * open (no jwt plugin) — this is how a client obtains a token in the first place.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TransactionalEventWriter events;

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
            TransactionalEventWriter events) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.events = events;
    }

    /** Request payload for both register and login. */
    public record Credentials(
            @NotBlank String username,
            @NotBlank String password) {
    }

    @PostMapping("/register")
    @Transactional
    public ResponseEntity<?> register(@RequestBody Credentials body) {
        if (body.username() == null || body.username().trim().isEmpty() || body.password() == null || body.password().trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "username and password are required"));
        }
        if (users.existsByUsername(body.username())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "username already taken"));
        }
        User user = new User(body.username(), passwordEncoder.encode(body.password()));
        users.saveAndFlush(user);
        write(EventTypes.USER_REGISTERED_V1, user,
                new UserRegistered(user.getUserId().toString(), user.getUsername(), Instant.now()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "user registered", "userId", user.getUserId().toString(),
                        "username", user.getUsername()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Credentials body) {
        return users.findByUsername(body.username())
                .filter(u -> passwordEncoder.matches(body.password(), u.getPasswordHash()))
                .filter(User::isActive)
                .map(u -> {
                    String token = jwtService.issueToken(u);
                    return ResponseEntity.ok(Map.of(
                            "access_token", token,
                            "token_type", "Bearer",
                            "expires_in_minutes", jwtService.getExpirationMinutes(),
                            "userId", u.getUserId().toString(), "username", u.getUsername()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "invalid username or password")));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "missing or invalid token"));
        }
        try {
            String token = authorization.substring(7);
            JwtService.Identity identity = jwtService.extractIdentity(token);
            var user = findIdentity(identity);
            return user.filter(User::isActive)
                    .map(u -> ResponseEntity.ok(Map.of("userId", u.getUserId().toString(), "username", u.getUsername())))
                    .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "user not found")));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid or expired token"));
        }
    }

    public record ProfileUpdate(@NotBlank String username) {}

    @PutMapping("/profile")
    @Transactional
    public ResponseEntity<?> updateProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody ProfileUpdate body) {
        try {
            User user = currentUser(authorization);
            String username = body.username().trim();
            if (!username.equals(user.getUsername()) && users.existsByUsername(username))
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "username already taken"));
            user.rename(username); users.saveAndFlush(user);
            write(EventTypes.USER_PROFILE_UPDATED_V1, user,
                    new UserProfileUpdated(user.getUserId().toString(), user.getUsername(), Instant.now()));
            return ResponseEntity.ok(Map.of("userId", user.getUserId().toString(), "username", user.getUsername()));
        } catch (Exception e) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid token")); }
    }

    @DeleteMapping("/me")
    @Transactional
    public ResponseEntity<?> deactivate(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            User user = currentUser(authorization);
            if (!user.isActive()) return ResponseEntity.noContent().build();
            user.deactivate(); users.saveAndFlush(user);
            write(EventTypes.USER_DEACTIVATED_V1, user,
                    new UserDeactivated(user.getUserId().toString(), Instant.now()));
            return ResponseEntity.noContent().build();
        } catch (Exception e) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "invalid token")); }
    }

    private User currentUser(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new IllegalArgumentException("missing token");
        return findIdentity(jwtService.extractIdentity(authorization.substring(7)))
                .orElseThrow(() -> new IllegalArgumentException("user not found"));
    }

    private void write(String eventType, User user, Object payload) {
        events.write(EventEnvelope.fact(eventType, 1, "auth-service", "user", user.getUserId().toString(),
                user.getVersion() + 1, UUID.randomUUID(), null, null, payload));
    }

    private java.util.Optional<User> findIdentity(JwtService.Identity identity) {
        try { return users.findByUserId(UUID.fromString(identity.subject())); }
        catch (IllegalArgumentException legacyToken) { return users.findByUsername(identity.subject()); }
    }
}
