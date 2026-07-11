package com.example.tweeter.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.tweeter.model.Post;

public interface PostRepository extends JpaRepository<Post, Long> {

    List<Post> findByAuthorUsernameOrderByCreatedAtDescIdDesc(String authorUsername);

    @Query(value = "SELECT * FROM posts WHERE id > :afterId ORDER BY id ASC LIMIT :limit", nativeQuery = true)
    List<Post> exportAfter(@Param("afterId") long afterId, @Param("limit") int limit);

    @Query(value = """
            SELECT p.*
            FROM posts p
            JOIN follows f ON f.followee_username = p.author_username
            WHERE f.follower_username = :username
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Post> findFeedFirstPage(@Param("username") String username, @Param("limit") int limit);

    @Query(value = """
            SELECT p.*
            FROM posts p
            JOIN follows f ON f.followee_username = p.author_username
            WHERE f.follower_username = :username
              AND (p.created_at < :createdAt OR (p.created_at = :createdAt AND p.id < :id))
            ORDER BY p.created_at DESC, p.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Post> findFeedAfterCursor(
            @Param("username") String username,
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            @Param("limit") int limit);
}
