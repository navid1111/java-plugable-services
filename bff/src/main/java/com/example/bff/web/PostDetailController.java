package com.example.bff.web;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.example.bff.dto.PostDetail;
import com.example.bff.service.PostDetailComposer;

/** One client call returns the composed post detail; no service database is shared. */
@RestController
public class PostDetailController {

    private final PostDetailComposer composer;

    public PostDetailController(PostDetailComposer composer) {
        this.composer = composer;
    }

    @GetMapping("/bff/posts/{id}")
    public PostDetail postDetail(
            @PathVariable long id,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestHeader(value = "traceparent", required = false) String traceparent) {
        return composer.compose(id, authorization, traceparent);
    }
}
