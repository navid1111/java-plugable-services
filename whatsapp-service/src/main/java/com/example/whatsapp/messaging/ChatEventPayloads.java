package com.example.whatsapp.messaging;

import java.time.Instant;

/** Payload shapes for chat-owned events. Private content; restricted consumers only. */
public final class ChatEventPayloads {

    public record ChatMessageCreated(Long messageId, Long chatId, String senderUsername,
            String content, int recipientCount, Instant createdAt) {
    }

    public record ChatMessageRead(Long messageId, Long chatId, String readerUsername, Instant readAt) {
    }

    private ChatEventPayloads() {
    }
}
