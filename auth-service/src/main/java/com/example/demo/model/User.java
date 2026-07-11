package com.example.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import java.util.UUID;

/**
 * Application user. Stored in the app's own Postgres.
 * Table is named "users" because "user" is a reserved word in Postgres.
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true, updatable = false)
    private UUID userId;

    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt hash — never the plaintext password. */
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    protected User() {
        // required by JPA
    }

    public User(String username, String passwordHash) {
        this.username = username;
        this.passwordHash = passwordHash;
    }

    public Long getId() {
        return id;
    }

    @PrePersist
    void assignStableId() { if (userId == null) userId = UUID.randomUUID(); }

    public UUID getUserId() { return userId; }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }
}
