package com.example.whatsapp.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.whatsapp.model.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query(value = """
            SELECT m.*
            FROM messages m
            WHERE m.chat_id = :chatId
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Message> findFirstPage(@Param("chatId") Long chatId, @Param("limit") int limit);

    @Query(value = """
            SELECT m.*
            FROM messages m
            WHERE m.chat_id = :chatId
              AND (m.created_at < :createdAt OR (m.created_at = :createdAt AND m.id < :id))
            ORDER BY m.created_at DESC, m.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<Message> findAfterCursor(
            @Param("chatId") Long chatId,
            @Param("createdAt") Instant createdAt,
            @Param("id") Long id,
            @Param("limit") int limit);
}
