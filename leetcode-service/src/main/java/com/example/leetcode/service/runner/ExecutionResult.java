package com.example.leetcode.service.runner;

public class ExecutionResult {
    private String status; // ACCEPTED, WRONG_ANSWER, TIME_LIMIT_EXCEEDED, RUNTIME_ERROR, COMPILE_ERROR
    private int passedCount;
    private int totalCount;
    private int executionTimeMs;
    private String errorMessage;

    public ExecutionResult() {}

    public ExecutionResult(String status, int passedCount, int totalCount, int executionTimeMs, String errorMessage) {
        this.status = status;
        this.passedCount = passedCount;
        this.totalCount = totalCount;
        this.executionTimeMs = executionTimeMs;
        this.errorMessage = errorMessage;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getPassedCount() { return passedCount; }
    public void setPassedCount(int passedCount) { this.passedCount = passedCount; }

    public int getTotalCount() { return totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }

    public int getExecutionTimeMs() { return executionTimeMs; }
    public void setExecutionTimeMs(int executionTimeMs) { this.executionTimeMs = executionTimeMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
