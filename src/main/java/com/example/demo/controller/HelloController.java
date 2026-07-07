package com.example.demo.controller;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.service.JwtService;

/**
 * Demo endpoints for the API gateway.
 *
 * Everything under /api is protected by Kong (jwt + rate-limiting). By the time
 * a request reaches here, Kong has already verified the token's signature and
 * expiry. Kong forwards the Authorization header, so we decode the `sub` claim
 * to know which end user is calling.
 */
@RestController
public class HelloController {

    private final JwtService jwtService;

    public HelloController(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @GetMapping("/")
    public String hello() {
        return "Hello from Spring Boot!";
    }

    @GetMapping("/api/hello")
    public Map<String, Object> apiHello(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String user = "unknown";
        if (authorization != null && authorization.startsWith("Bearer ")) {
            user = jwtService.extractUsername(authorization.substring(7));
        }
        return Map.of(
                "message", "Hello from the protected Spring Boot API!",
                "user", user,
                "timestamp", Instant.now().toString());
    }
}
