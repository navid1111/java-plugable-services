package com.example.postsearch.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.postsearch.model.SearchDocument;

public interface SearchDocumentRepository extends JpaRepository<SearchDocument, Long> {

    Optional<SearchDocument> findByTargetTypeAndTargetId(String targetType, String targetId);

    @Query(value = """
            SELECT d.*
            FROM search_documents d
            WHERE d.id IN (
                SELECT e.document_id
                FROM search_term_entries e
                WHERE e.term IN (:terms)
                GROUP BY e.document_id
                HAVING COUNT(DISTINCT e.term) = :termCount
            )
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchDocument> searchRecencyFirst(
            @Param("terms") Collection<String> terms,
            @Param("termCount") int termCount,
            @Param("limit") int limit);

    @Query(value = """
            SELECT d.*
            FROM search_documents d
            WHERE d.id IN (
                SELECT e.document_id
                FROM search_term_entries e
                WHERE e.term IN (:terms)
                GROUP BY e.document_id
                HAVING COUNT(DISTINCT e.term) = :termCount
            )
              AND (d.created_at < :createdAt OR (d.created_at = :createdAt AND d.id < :id))
            ORDER BY d.created_at DESC, d.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchDocument> searchRecencyAfterCursor(
            @Param("terms") Collection<String> terms,
            @Param("termCount") int termCount,
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            @Param("limit") int limit);

    @Query(value = """
            SELECT d.*
            FROM search_documents d
            WHERE d.id IN (
                SELECT e.document_id
                FROM search_term_entries e
                WHERE e.term IN (:terms)
                GROUP BY e.document_id
                HAVING COUNT(DISTINCT e.term) = :termCount
            )
            ORDER BY d.like_count DESC, d.created_at DESC, d.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchDocument> searchLikesFirst(
            @Param("terms") Collection<String> terms,
            @Param("termCount") int termCount,
            @Param("limit") int limit);

    @Query(value = """
            SELECT d.*
            FROM search_documents d
            WHERE d.id IN (
                SELECT e.document_id
                FROM search_term_entries e
                WHERE e.term IN (:terms)
                GROUP BY e.document_id
                HAVING COUNT(DISTINCT e.term) = :termCount
            )
              AND (
                d.like_count < :likeCount
                OR (d.like_count = :likeCount AND d.created_at < :createdAt)
                OR (d.like_count = :likeCount AND d.created_at = :createdAt AND d.id < :id)
              )
            ORDER BY d.like_count DESC, d.created_at DESC, d.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<SearchDocument> searchLikesAfterCursor(
            @Param("terms") Collection<String> terms,
            @Param("termCount") int termCount,
            @Param("likeCount") int likeCount,
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            @Param("limit") int limit);
}
