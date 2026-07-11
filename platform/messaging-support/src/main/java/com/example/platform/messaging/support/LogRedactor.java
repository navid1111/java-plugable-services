package com.example.platform.messaging.support;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks sensitive values before they reach logs: the value following a sensitive key in
 * {@code key=value} or JSON {@code "key":"value"} form, and bearer tokens. Covers passwords,
 * hashes, tokens, secrets, API/private keys, and submission source {@code code}. Keys are
 * matched, not free prose, so ordinary text is left intact.
 */
public final class LogRedactor {

    private static final String MASK = "***";

    private static final Pattern SENSITIVE_KV = Pattern.compile(
            "(?i)(\"?\\b(?:password|passwordhash|token|secret|api[_-]?key|"
                    + "private[_-]?key|code)\"?\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,}]+)");

    // Authorization credentials: mask everything after the scheme (keeping the scheme).
    private static final Pattern AUTH_SCHEME = Pattern.compile(
            "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._\\-=+/]+");

    private LogRedactor() {
    }

    public static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String authMasked = AUTH_SCHEME.matcher(message)
                .replaceAll(mr -> Matcher.quoteReplacement(mr.group(1)) + " " + MASK);
        return SENSITIVE_KV.matcher(authMasked)
                .replaceAll(mr -> Matcher.quoteReplacement(mr.group(1)) + MASK);
    }
}
