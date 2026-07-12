package com.example.leetcode.security;

import com.example.platform.messaging.support.UserJwtVerifier;
import com.example.platform.messaging.support.JwtIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JwtHelper {
    private final UserJwtVerifier verifier;
    public JwtHelper(ObjectMapper mapper, @Value("${jwt.secret}") String secret,
            @Value("${jwt.issuer}") String issuer) {
        verifier = new UserJwtVerifier(secret, issuer, mapper);
    }
    public String extractUsername(String authorization) {
        return verifier.verifyUser(authorization).username();
    }
    public JwtIdentity extractIdentity(String authorization) { return verifier.verifyUser(authorization); }
}
