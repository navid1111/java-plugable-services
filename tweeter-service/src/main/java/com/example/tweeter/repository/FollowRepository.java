package com.example.tweeter.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.tweeter.model.Follow;

public interface FollowRepository extends JpaRepository<Follow, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO follows (follower_username, followee_username, created_at)
            VALUES (:followerUsername, :followeeUsername, CURRENT_TIMESTAMP)
            ON CONFLICT (follower_username, followee_username) DO NOTHING
            """, nativeQuery = true)
    int insertIfMissing(
            @Param("followerUsername") String followerUsername,
            @Param("followeeUsername") String followeeUsername);

    int deleteByFollowerUsernameAndFolloweeUsername(String followerUsername, String followeeUsername);
}
