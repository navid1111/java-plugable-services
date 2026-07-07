package com.example.whatsapp.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.whatsapp.model.Chat;

public interface ChatRepository extends JpaRepository<Chat, Long> {

    @Query(value = """
            SELECT c.*
            FROM chats c
            JOIN chat_participants cp ON cp.chat_id = c.id
            WHERE cp.username = :username
            ORDER BY c.created_at DESC, c.id DESC
            """, nativeQuery = true)
    List<Chat> findByParticipant(@Param("username") String username);
}
