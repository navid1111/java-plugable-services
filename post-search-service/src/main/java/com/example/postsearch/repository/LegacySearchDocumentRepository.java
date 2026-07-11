package com.example.postsearch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import com.example.postsearch.model.LegacySearchDocument;

public interface LegacySearchDocumentRepository
        extends JpaRepository<LegacySearchDocument, LegacySearchDocument.Key> {}
