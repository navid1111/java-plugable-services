package com.example.leetcode.messaging;

public record JudgeCompleted(Long submissionId, String status, int passedCount, int totalCount,
                             int executionTimeMs, String errorMessage) {}
