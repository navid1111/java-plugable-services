package com.example.bff.service;

/** A critical dependency (the post owner) failed or timed out — the composed read is a 502. */
public class CriticalDependencyException extends RuntimeException {
    private final String dependency;

    public CriticalDependencyException(String dependency, Throwable cause) {
        super("critical dependency '" + dependency + "' unavailable", cause);
        this.dependency = dependency;
    }

    public String dependency() {
        return dependency;
    }
}
