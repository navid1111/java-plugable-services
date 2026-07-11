package com.example.leetcode.messaging;

public record JudgeRequested(Long submissionId, String problemId, String language, String code,
                             String testCasesJson, int timeoutSeconds) {}
