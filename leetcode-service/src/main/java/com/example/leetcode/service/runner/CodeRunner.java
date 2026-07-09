package com.example.leetcode.service.runner;

public interface CodeRunner {
    boolean supports(String language);
    ExecutionResult runCode(String code, String testCasesJson);
}
