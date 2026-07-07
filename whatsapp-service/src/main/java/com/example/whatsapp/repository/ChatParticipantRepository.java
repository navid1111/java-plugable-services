package com.example.whatsapp.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.whatsapp.model.ChatParticipant;

public interface ChatParticipantRepository extends JpaRepository<ChatParticipant, Long> {

    boolean existsByChatIdAndUsername(Long chatId, String username);

    List<ChatParticipant> findByChatIdOrderByUsernameAsc(Long chatId);

    List<ChatParticipant> findByChatIdIn(Collection<Long> chatIds);
}
