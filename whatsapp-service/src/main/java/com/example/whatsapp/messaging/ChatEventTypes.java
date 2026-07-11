package com.example.whatsapp.messaging;

/**
 * Event types owned by whatsapp-service. These exist only for external reactions
 * (push, moderation, analytics); the database and WebSocket remain the delivery source
 * of truth. Kept local so the shared {@code EventTypes} registry stays owned by producers.
 */
public final class ChatEventTypes {
    public static final String CHAT_MESSAGE_CREATED_V1 = "chat.message-created.v1";
    public static final String CHAT_MESSAGE_READ_V1 = "chat.message-read.v1";

    private ChatEventTypes() {
    }
}
