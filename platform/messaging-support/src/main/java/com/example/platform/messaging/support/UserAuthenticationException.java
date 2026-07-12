package com.example.platform.messaging.support;

public class UserAuthenticationException extends IllegalArgumentException {
    public UserAuthenticationException(String message) { super(message); }
    public UserAuthenticationException(String message, Throwable cause) { super(message, cause); }
}
