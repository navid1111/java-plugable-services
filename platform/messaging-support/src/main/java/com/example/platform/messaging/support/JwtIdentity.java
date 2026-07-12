package com.example.platform.messaging.support;

/** Stable user identity. Username is presentation data and must not be used as a relational key. */
public record JwtIdentity(String userId, String username) {}
