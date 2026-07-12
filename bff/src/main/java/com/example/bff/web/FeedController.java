package com.example.bff.web;

import com.example.bff.dto.ComposedFeed;
import com.example.bff.service.FeedComposer;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FeedController {
    private final FeedComposer composer;

    public FeedController(FeedComposer composer) {
        this.composer = composer;
    }

    @GetMapping("/bff/feed")
    public ComposedFeed feed(@RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "traceparent", required = false) String traceparent) {
        return composer.compose(cursor, pageSize, authorization, traceparent);
    }
}
