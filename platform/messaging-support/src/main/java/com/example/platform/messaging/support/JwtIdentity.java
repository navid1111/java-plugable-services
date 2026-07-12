package com.example.platform.messaging.support;

/** Transitional JWT identity extraction: stable UUID subject plus username display claim. */
public record JwtIdentity(String userId, String username, boolean legacyToken) {}
