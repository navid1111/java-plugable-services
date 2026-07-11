package com.example.whatsapp.messaging;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.example.whatsapp.model.Message;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.support.OutboxMessage;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.support.SafeEventSerializer;

/**
 * Writes chat domain events to the shared outbox in the same transaction as the message
 * persistence. Because persistence and the outbox commit together and publishing happens
 * later from the outbox, a broker outage cannot lose a message or its event: the message is
 * stored (and delivered over the DB/WebSocket path) regardless, and the event drains when
 * the broker returns.
 */
@Component
public class ChatEventPublisher {

    private static final String PRODUCER = "whatsapp-service";

    private final OutboxMessageRepository outbox;
    private final SafeEventSerializer serializer;

    public ChatEventPublisher(OutboxMessageRepository outbox, SafeEventSerializer serializer) {
        this.outbox = outbox;
        this.serializer = serializer;
    }

    public void messageCreated(Message message, int recipientCount) {
        emit(ChatEventTypes.CHAT_MESSAGE_CREATED_V1, "chat", message.getChatId(), message.getId(),
                new ChatEventPayloads.ChatMessageCreated(message.getId(), message.getChatId(),
                        message.getSenderUsername(), message.getContent(), recipientCount,
                        message.getCreatedAt()));
    }

    public void messageRead(Long messageId, Long chatId, String readerUsername) {
        emit(ChatEventTypes.CHAT_MESSAGE_READ_V1, "message", messageId, 1L,
                new ChatEventPayloads.ChatMessageRead(messageId, chatId, readerUsername, Instant.now()));
    }

    private void emit(String eventType, String aggregateType, Long aggregateId, long aggregateVersion,
            Object payload) {
        EventEnvelope<Object> envelope = EventEnvelope.fact(eventType, 1, PRODUCER,
                aggregateType, String.valueOf(aggregateId), aggregateVersion, null, null, null, payload);
        outbox.save(new OutboxMessage(envelope.eventId(), aggregateType, String.valueOf(aggregateId),
                eventType, 1, serializer.serialize(envelope), Instant.now()));
    }
}
