package com.example.demo.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.model.User;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);
    Optional<User> findByUserId(UUID userId);

    boolean existsByUsername(String username);

    @Query(value = "SELECT * FROM users WHERE id > :afterId ORDER BY id LIMIT :limit", nativeQuery = true)
    List<User> exportAfter(@Param("afterId") long afterId, @Param("limit") int limit);
}
