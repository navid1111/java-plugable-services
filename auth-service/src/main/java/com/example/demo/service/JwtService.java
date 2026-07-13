package com.example.demo.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import com.example.demo.model.User;

/**
 * Mints and verifies HS256 JWTs.
 *
 * The secret and issuer here MUST match the jwt credential registered in Kong
 * (kong/setup.sh): Kong looks the token up by its `iss` claim (= credential key)
 * and verifies the signature with the same shared secret.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final String issuer;
    private final long expirationMinutes;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.issuer}") String issuer,
            @Value("${jwt.expiration-minutes}") long expirationMinutes) {
        // HS256 needs >= 256-bit (32-byte) key material.
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
        this.expirationMinutes = expirationMinutes;
    }

    /** Issue a token whose subject is the immutable user ID; username is a display claim. */
    public String issueToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(expirationMinutes, ChronoUnit.MINUTES);
        java.util.List<String> roles = user.isAdmin()
                ? java.util.List.of("USER", "ADMIN")
                : java.util.List.of("USER");
        String scope = user.isAdmin() ? "user admin" : "user";
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getUserId().toString())
                .claim("username", user.getUsername())
                .claim("roles", roles)
                .claim("scope", scope)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    public record Identity(String subject, String username, java.util.Set<String> roles) {}

    /** Verify signature + expiry and return stable subject plus display username. */
    public Identity extractIdentity(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);
        java.util.List<?> roles = jws.getPayload().get("roles", java.util.List.class);
        String scope = jws.getPayload().get("scope", String.class);
        try { java.util.UUID.fromString(jws.getPayload().getSubject()); }
        catch (IllegalArgumentException invalidSubject) {
            throw new io.jsonwebtoken.JwtException("JWT subject must be a stable user UUID");
        }
        if (roles == null || !roles.contains("USER") || scope == null
                || java.util.Arrays.stream(scope.split(" +")).noneMatch("user"::equals)){
            throw new io.jsonwebtoken.JwtException("required user authority missing");
        }
        return new Identity(jws.getPayload().getSubject(),
                jws.getPayload().get("username", String.class),
                java.util.Set.copyOf(roles.stream().map(String::valueOf).toList()));
    }
}
