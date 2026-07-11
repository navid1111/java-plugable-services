package com.example.comment.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.comment.model.Comment;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    long countByTargetTypeAndTargetId(String targetType, String targetId);

    @Query(value = """
            SELECT c.*
            FROM comments c
            WHERE c.target_type = :targetType
              AND c.target_id = :targetId
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Comment> findTargetFirstPage(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("limit") int limit);

    @Query(value = """
            SELECT c.*
            FROM comments c
            WHERE c.target_type = :targetType
              AND c.target_id = :targetId
              AND (c.created_at < :createdAt OR (c.created_at = :createdAt AND c.id < :id))
            ORDER BY c.created_at DESC, c.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Comment> findTargetAfterCursor(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            @Param("limit") int limit);
}
