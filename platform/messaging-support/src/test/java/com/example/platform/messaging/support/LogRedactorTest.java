package com.example.platform.messaging.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class LogRedactorTest {

    @Test
    void masksSensitiveKeyValuesButKeepsBenignFields() {
        String out = LogRedactor.redact("login password=hunter2 user=alice attempts=3");
        assertFalse(out.contains("hunter2"), out);
        assertTrue(out.contains("password=***"));
        assertTrue(out.contains("user=alice"), "non-sensitive fields survive");
        assertTrue(out.contains("attempts=3"));
    }

    @Test
    void masksTokenAndPasswordInJson() {
        String out = LogRedactor.redact("{\"token\":\"a.b.c\",\"passwordHash\":\"$2a$x\",\"n\":1}");
        assertFalse(out.contains("a.b.c"));
        assertFalse(out.contains("$2a$x"));
        assertTrue(out.contains("\"n\":1"), "unrelated fields survive");
    }

    @Test
    void masksSubmissionSourceCode() {
        String out = LogRedactor.redact("judge code=\"class Solution { int x; }\" lang=java");
        assertFalse(out.contains("class Solution"));
        assertTrue(out.contains("lang=java"));
    }

    @Test
    void masksBearerTokens() {
        String out = LogRedactor.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig");
        assertFalse(out.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertTrue(out.contains("Bearer ***"));
    }

    @Test
    void leavesBenignTextUnchanged() {
        String msg = "processed event post.created.v1 for aggregate 42 in 12ms";
        assertEquals(msg, LogRedactor.redact(msg));
    }
}
