package com.example.demo.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.JwtService;

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

    public AuthController(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    /** Request payload for both register and login. */
    public record Credentials(
            @NotBlank String username,
            @NotBlank String password) {
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody Credentials body) {
        if (users.existsByUsername(body.username())) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "username already taken"));
        }
        User user = new User(body.username(), passwordEncoder.encode(body.password()));
        users.save(user);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Map.of("message", "user registered", "username", user.getUsername()));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody Credentials body) {
        return users.findByUsername(body.username())
                .filter(u -> passwordEncoder.matches(body.password(), u.getPasswordHash()))
                .map(u -> {
                    String token = jwtService.issueToken(u.getUsername());
                    return ResponseEntity.ok(Map.of(
                            "access_token", token,
                            "token_type", "Bearer",
                            "expires_in_minutes", jwtService.getExpirationMinutes()));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("error", "invalid username or password")));
    }
}
