package com.example.postsearch.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.postsearch.model.SearchTermEntry;

public interface SearchTermEntryRepository extends JpaRepository<SearchTermEntry, Long> {

    @Modifying
    @Query("delete from SearchTermEntry e where e.documentId = :documentId")
    void deleteByDocumentId(@Param("documentId") Long documentId);
}
