package com.example.comment.security;
import org.springframework.stereotype.Component;
import com.example.platform.messaging.support.JwtIdentity;
import com.example.platform.messaging.support.UserJwtVerifier;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
@Component public class JwtHelper {
    private final UserJwtVerifier verifier;
    public JwtHelper(ObjectMapper mapper, @Value("${jwt.secret}") String secret,
            @Value("${jwt.issuer}") String issuer) { this.verifier = new UserJwtVerifier(secret, issuer, mapper); }
    public JwtIdentity extractIdentity(String authorization) { return verifier.verifyUser(authorization); }
    public String extractUsername(String authorization) { return extractIdentity(authorization).username(); }
}
