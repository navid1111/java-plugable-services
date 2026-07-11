package com.example.platform.messaging.leetcode;

public record JudgeCompleted(
        long submissionId,
        String status,
        int passedCount,
        int totalCount,
        int executionTimeMs,
        String errorMessage) {
}
