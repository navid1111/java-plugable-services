package com.example.tweeter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.tweeter.model.Follow;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO follows (follower_user_id, follower_username, followee_user_id, followee_username, created_at)
            VALUES (:followerUserId, :followerUsername, :followeeUserId, :followeeUsername, CURRENT_TIMESTAMP)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("followerUserId") String followerUserId,
            @Param("followerUsername") String followerUsername,
            @Param("followeeUserId") String followeeUserId,
            @Param("followeeUsername") String followeeUsername);

    int deleteByFollowerUserIdAndFolloweeUserId(String followerUserId, String followeeUserId);
}
