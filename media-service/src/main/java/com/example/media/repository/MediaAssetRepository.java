package com.example.media.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.media.model.MediaAsset;

public interface MediaAssetRepository extends JpaRepository<MediaAsset, Long> {

    Optional<MediaAsset> findByIdAndDeletedAtIsNull(Long id);
    List<MediaAsset> findByTargetTypeAndTargetIdAndDeletedAtIsNull(String targetType, String targetId);
    long countByTargetTypeAndTargetIdAndDeletedAtIsNull(String targetType, String targetId);

    @Query(value = """
            SELECT *
            FROM media_assets
            WHERE target_type = :targetType
              AND target_id = :targetId
              AND deleted_at IS NULL
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MediaAsset> findTargetFirstPage(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("limit") int limit);

    @Query(value = """
            SELECT *
            FROM media_assets
            WHERE target_type = :targetType
              AND target_id = :targetId
              AND deleted_at IS NULL
              AND (created_at < :createdAt OR (created_at = :createdAt AND id < :id))
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<MediaAsset> findTargetAfterCursor(
            @Param("targetType") String targetType,
            @Param("targetId") String targetId,
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            @Param("limit") int limit);
}
