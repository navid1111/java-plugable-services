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
        return Jwts.builder()
                .issuer(issuer)
                .subject(user.getUserId().toString())
                .claim("username", user.getUsername())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public long getExpirationMinutes() {
        return expirationMinutes;
    }

    public record Identity(String subject, String username) {}

    /** Verify signature + expiry and return stable subject plus display username. */
    public Identity extractIdentity(String token) {
        Jws<Claims> jws = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token);
        return new Identity(jws.getPayload().getSubject(),
                jws.getPayload().get("username", String.class));
    }
}
