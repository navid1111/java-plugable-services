package com.example.leetcode.security;

import com.example.platform.messaging.support.UserJwtVerifier;
import com.example.platform.messaging.support.JwtIdentity;
import com.example.platform.messaging.support.UserAuthenticationException;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
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

    public JwtIdentity requireAdmin(String authorization) {
        final UserJwtVerifier.Principal principal;
        try {
            principal = verifier.verify(authorization);
            principal.requireRole("USER");
            principal.requireScope("user");
        } catch (UserAuthenticationException invalid) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid or expired token");
        }
        if (!principal.roles().contains("ADMIN") || !principal.scopes().contains("admin")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator role required");
        }
        return principal.identity();
    }
}
