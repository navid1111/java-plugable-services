package com.example.tweeter.security;
import org.springframework.stereotype.Component;
import com.example.platform.messaging.support.JwtIdentity;
import tools.jackson.databind.ObjectMapper;
@Component public class JwtHelper {
    private final ObjectMapper mapper;
    public JwtHelper(ObjectMapper mapper) { this.mapper = mapper; }
    public JwtIdentity extractIdentity(String authorization) { return JwtIdentity.parse(authorization, mapper); }
    public String extractUsername(String authorization) { return extractIdentity(authorization).username(); }
}
