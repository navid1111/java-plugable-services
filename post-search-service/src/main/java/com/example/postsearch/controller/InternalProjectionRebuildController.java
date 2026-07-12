package com.example.postsearch.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.postsearch.service.PostProjectionRebuildService;
import com.example.postsearch.service.SearchShadowComparisonService;
import com.example.platform.messaging.support.WorkloadAuthenticationException;
import com.example.platform.messaging.support.WorkloadJwtVerifier;

@RestController
@RequestMapping("/internal/projections/posts")
public class InternalProjectionRebuildController {
    private final PostProjectionRebuildService rebuild;
    private final WorkloadJwtVerifier workloads;
    private final SearchShadowComparisonService shadow;

    public InternalProjectionRebuildController(PostProjectionRebuildService rebuild,
            SearchShadowComparisonService shadow,
            WorkloadJwtVerifier workloads) {
        this.rebuild = rebuild;
        this.shadow = shadow;
        this.workloads = workloads;
    }

    @GetMapping("/shadow-report")
    public ResponseEntity<?> shadowReport(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(authorization, "search:inspect")) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(shadow.compare());
    }

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "25") int maxPages,
            @RequestParam(defaultValue = "false") boolean reset) {
        if (!authorized(authorization, "search:rebuild")) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        if (reset) rebuild.resetCheckpoint();
        return ResponseEntity.ok(rebuild.rebuild(maxPages));
    }

    private boolean authorized(String authorization, String scope) {
        try { workloads.verify(authorization, scope); return true; }
        catch (WorkloadAuthenticationException denied) { return false; }
    }
}
