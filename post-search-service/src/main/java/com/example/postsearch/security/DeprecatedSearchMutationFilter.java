package com.example.postsearch.security;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Ensures removed public mutation routes do not degrade into method-discovery responses. */
@Component
public class DeprecatedSearchMutationFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if ("PUT".equals(request.getMethod())
                && request.getRequestURI().startsWith("/post-search/documents/")) {
            response.sendError(HttpStatus.NOT_FOUND.value());
            return;
        }
        chain.doFilter(request, response);
    }
}
