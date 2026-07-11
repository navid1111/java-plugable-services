package com.example.platform.messaging.leetcode;

public record JudgeRequested(
        long submissionId,
        String problemId,
        String language,
        String code,
        String testCasesJson,
        int timeoutSeconds) {
}
