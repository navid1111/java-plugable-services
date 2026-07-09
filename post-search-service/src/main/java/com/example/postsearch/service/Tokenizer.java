package com.example.postsearch.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class Tokenizer {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]+");

    public List<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }

        String normalized = value.toLowerCase(Locale.ROOT);
        String[] rawTerms = NON_ALPHANUMERIC.split(normalized);
        Set<String> terms = new LinkedHashSet<>();
        for (String rawTerm : rawTerms) {
            if (!rawTerm.isBlank()) {
                terms.add(rawTerm);
            }
        }
        return new ArrayList<>(terms);
    }
}
