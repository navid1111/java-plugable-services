package com.example.bff.service;

/** The authoritative post does not exist — the composed read is a 404. */
public class PostNotFoundException extends RuntimeException {
    private final long id;

    public PostNotFoundException(long id) {
        super("post " + id + " not found");
        this.id = id;
    }

    public long id() {
        return id;
    }
}
