package com.example.bff.service;

/** The post exists but is deleted/tombstoned — a visibility check turns it into a 410. */
public class PostGoneException extends RuntimeException {
    private final long id;

    public PostGoneException(long id) {
        super("post " + id + " is deleted");
        this.id = id;
    }

    public long id() {
        return id;
    }
}
