package com.example.bff.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.bff.service.CriticalDependencyException;
import com.example.bff.service.PostGoneException;
import com.example.bff.service.PostNotFoundException;

/** Maps composition failures to RFC 9457 {@code application/problem+json} responses. */
@RestControllerAdvice
public class CompositionProblemHandler {

    private static final String BASE = "https://errors.example.local/";

    @ExceptionHandler(PostNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(PostNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "Post not found", ex.getMessage(), "post-not-found");
    }

    @ExceptionHandler(PostGoneException.class)
    public ResponseEntity<ProblemDetail> gone(PostGoneException ex) {
        return problem(HttpStatus.GONE, "Post deleted", ex.getMessage(), "post-gone");
    }

    @ExceptionHandler(CriticalDependencyException.class)
    public ResponseEntity<ProblemDetail> critical(CriticalDependencyException ex) {
        ResponseEntity<ProblemDetail> response = problem(HttpStatus.BAD_GATEWAY,
                "Upstream dependency unavailable", ex.getMessage(), "critical-dependency-unavailable");
        response.getBody().setProperty("dependency", ex.dependency());
        return response;
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail, String slug) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setTitle(title);
        body.setType(URI.create(BASE + slug));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
