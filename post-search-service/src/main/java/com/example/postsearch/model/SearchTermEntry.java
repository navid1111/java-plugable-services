package com.example.postsearch.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "search_term_entries",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_search_terms_document", columnNames = {"term", "document_id"})
        },
        indexes = {
                @Index(name = "idx_search_terms_term_document", columnList = "term, document_id"),
                @Index(name = "idx_search_terms_document", columnList = "document_id")
        })
public class SearchTermEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String term;

    @Column(name = "document_id", nullable = false)
    private Long documentId;

    protected SearchTermEntry() {
        // required by JPA
    }

    public SearchTermEntry(String term, Long documentId) {
        this.term = term;
        this.documentId = documentId;
    }

    public Long getId() {
        return id;
    }

    public String getTerm() {
        return term;
    }

    public Long getDocumentId() {
        return documentId;
    }
}
