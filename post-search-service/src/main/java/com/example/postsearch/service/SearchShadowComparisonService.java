package com.example.postsearch.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.postsearch.model.LegacySearchDocument;
import com.example.postsearch.repository.LegacySearchDocumentRepository;
import com.example.postsearch.repository.SearchDocumentRepository;

@Service
public class SearchShadowComparisonService {
    private final LegacySearchDocumentRepository legacy;
    private final SearchDocumentRepository projections;

    public SearchShadowComparisonService(LegacySearchDocumentRepository legacy,
            SearchDocumentRepository projections) {
        this.legacy = legacy; this.projections = projections;
    }

    @Transactional
    public void observeLegacyWrite(String type, String id, String author, String content, Instant createdAt) {
        legacy.save(new LegacySearchDocument(type, id, author, content, createdAt));
    }

    public record Mismatch(String targetType, String targetId, String reason) {}
    public record Report(int compared, List<Mismatch> mismatches) {
        public boolean reconciled() { return mismatches.isEmpty(); }
    }

    @Transactional(readOnly = true)
    public Report compare() {
        List<Mismatch> mismatches = new ArrayList<>();
        var rows = legacy.findAll();
        for (LegacySearchDocument expected : rows) {
            var actual = projections.findByTargetTypeAndTargetId(
                    expected.getTargetType(), expected.getTargetId());
            if (actual.isEmpty()) {
                mismatches.add(new Mismatch(expected.getTargetType(), expected.getTargetId(), "missing projection"));
            } else if (actual.get().isDeleted()) {
                mismatches.add(new Mismatch(expected.getTargetType(), expected.getTargetId(), "projection deleted"));
            } else if (!expected.getAuthorUsername().equals(actual.get().getAuthorUsername())
                    || !expected.getContent().equals(actual.get().getContent())
                    || !expected.getCreatedAt().equals(actual.get().getCreatedAt())) {
                mismatches.add(new Mismatch(expected.getTargetType(), expected.getTargetId(), "snapshot differs"));
            }
        }
        return new Report(rows.size(), List.copyOf(mismatches));
    }
}
