package com.example.platform.messaging.support;

public class WorkloadAuthenticationException extends RuntimeException {
    public WorkloadAuthenticationException(String message) { super(message); }
    public WorkloadAuthenticationException(String message, Throwable cause) { super(message, cause); }
}
