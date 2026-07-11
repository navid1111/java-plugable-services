package com.example.postsearch.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.postsearch.service.PostProjectionRebuildService;

@RestController
@RequestMapping("/internal/projections/posts")
public class InternalProjectionRebuildController {
    private final PostProjectionRebuildService rebuild;
    private final byte[] expectedToken;

    public InternalProjectionRebuildController(PostProjectionRebuildService rebuild,
            @Value("${internal.service.token}") String token) {
        this.rebuild = rebuild;
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(
            @RequestHeader(value = "X-Internal-Service-Token", required = false) String token,
            @RequestParam(defaultValue = "25") int maxPages,
            @RequestParam(defaultValue = "false") boolean reset) {
        if (token == null || !MessageDigest.isEqual(expectedToken,
                token.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (reset) rebuild.resetCheckpoint();
        return ResponseEntity.ok(rebuild.rebuild(maxPages));
    }
}
