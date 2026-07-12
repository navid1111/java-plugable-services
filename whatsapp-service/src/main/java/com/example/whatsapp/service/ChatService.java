package com.example.whatsapp.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.whatsapp.model.Chat;
import com.example.whatsapp.model.ChatParticipant;
import com.example.whatsapp.model.InboxEntry;
import com.example.whatsapp.model.Message;
import com.example.whatsapp.repository.ChatParticipantRepository;
import com.example.whatsapp.repository.ChatRepository;
import com.example.whatsapp.repository.InboxEntryRepository;
import com.example.whatsapp.repository.MessageRepository;

@Service
public class ChatService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_PARTICIPANTS = 100;

    private final ChatRepository chats;
    private final ChatParticipantRepository participants;
    private final MessageRepository messages;
    private final InboxEntryRepository inboxEntries;
    private final com.example.whatsapp.messaging.ChatEventPublisher events;

    public ChatService(
            ChatRepository chats,
            ChatParticipantRepository participants,
            MessageRepository messages,
            InboxEntryRepository inboxEntries,
            com.example.whatsapp.messaging.ChatEventPublisher events) {
        this.chats = chats;
        this.participants = participants;
        this.messages = messages;
        this.inboxEntries = inboxEntries;
        this.events = events;
    }

    public record ChatView(Long id, String name, List<String> participants, Instant createdAt) {
    }
    public record UserRef(String userId, String username) {}

    public record MessageView(Long id, Long chatId, String senderUsername, String content, Instant createdAt) {
        static MessageView from(Message message) {
            return new MessageView(
                    message.getId(),
                    message.getChatId(),
                    message.getSenderUsername(),
                    message.getContent(),
                    message.getCreatedAt());
        }
    }

    public record MessagePage(List<MessageView> items, String nextCursor) {
    }

    public record Delivery(MessageView message, List<String> recipientUserIds) {
    }

    private record MessageCursor(Instant createdAt, Long id) {
    }

    @Transactional
    public ChatView createChat(String creatorUserId, String creatorUsername,
            String requestedName, List<UserRef> requestedParticipants) {
        UserRef creator = requireUser(new UserRef(creatorUserId, creatorUsername));
        Map<String, UserRef> uniqueParticipants = new LinkedHashMap<>();
        uniqueParticipants.put(creator.userId(), creator);
        if (requestedParticipants != null) {
            for (UserRef participant : requestedParticipants) {
                UserRef valid = requireUser(participant);
                uniqueParticipants.putIfAbsent(valid.userId(), valid);
            }
        }

        if (uniqueParticipants.size() < 2) {
            throw new IllegalArgumentException("a chat needs at least two participants");
        }
        if (uniqueParticipants.size() > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("a chat can have at most 100 participants");
        }

        Chat chat = chats.save(new Chat(normalizeOptional(requestedName)));
        List<ChatParticipant> rows = uniqueParticipants.values().stream()
                .map(user -> new ChatParticipant(chat.getId(), user.userId(), user.username()))
                .toList();
        participants.saveAll(rows);
        return toView(chat, rows);
    }

    @Transactional(readOnly = true)
    public List<ChatView> findMyChats(String userId) {
        List<Chat> mine = chats.findByParticipant(requireUserId(userId));
        if (mine.isEmpty()) {
            return List.of();
        }

        List<Long> chatIds = mine.stream().map(Chat::getId).toList();
        Map<Long, List<ChatParticipant>> participantsByChat = participants.findByChatIdIn(chatIds).stream()
                .collect(Collectors.groupingBy(ChatParticipant::getChatId));

        return mine.stream()
                .map(chat -> toView(chat, participantsByChat.getOrDefault(chat.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public MessagePage findMessages(String userId, Long chatId, String cursor, int requestedPageSize) {
        requireParticipant(userId, chatId);
        int pageSize = clampPageSize(requestedPageSize);
        int fetchSize = pageSize + 1;

        List<Message> fetched;
        if (cursor == null || cursor.isBlank()) {
            fetched = messages.findFirstPage(chatId, fetchSize);
        } else {
            MessageCursor parsed = decodeCursor(cursor);
            fetched = messages.findAfterCursor(chatId, parsed.createdAt(), parsed.id(), fetchSize);
        }

        boolean hasMore = fetched.size() > pageSize;
        List<Message> pageItems = hasMore ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = hasMore ? encodeCursor(pageItems.get(pageItems.size() - 1)) : null;
        return new MessagePage(pageItems.stream().map(MessageView::from).toList(), nextCursor);
    }

    @Transactional
    public Delivery sendMessage(String senderUserId, String senderUsername, Long chatId, String content) {
        String stableSenderId = requireUserId(senderUserId);
        String sender = requireText(senderUsername, "sender username");
        requireParticipant(stableSenderId, chatId);
        String trimmed = requireText(content, "content");
        if (trimmed.length() > 2000) {
            throw new IllegalArgumentException("content must be 2000 characters or fewer");
        }

        List<ChatParticipant> chatParticipants = participantRows(chatId);
        Message message = messages.save(new Message(chatId, stableSenderId, sender, trimmed));
        List<ChatParticipant> recipients = chatParticipants.stream()
                .filter(participant -> !participant.getUserId().equals(stableSenderId))
                .toList();
        inboxEntries.saveAll(recipients.stream()
                .map(recipient -> new InboxEntry(message.getId(), recipient.getUserId(), recipient.getUsername()))
                .toList());

        // External-reaction event (push/moderation/analytics); DB + WebSocket stay the
        // delivery source of truth. Same transaction as the message: never lost, never
        // published for a message that rolled back.
        events.messageCreated(message, recipients.size());

        return new Delivery(MessageView.from(message), recipients.stream().map(ChatParticipant::getUserId).toList());
    }

    @Transactional
    public void ack(String userId, String username, Long messageId) {
        String recipientId = requireUserId(userId);
        String recipientName = requireText(username, "username");
        if (messageId == null) {
            return;
        }
        Optional<InboxEntry> maybeEntry = inboxEntries.findByMessageIdAndRecipientUserId(messageId, recipientId);
        Optional<InboxEntry> undelivered = maybeEntry.filter(entry -> !entry.isDelivered());
        undelivered.ifPresent(entry -> {
            entry.markDelivered();
            // Emit only on the first read transition, so repeated acks stay idempotent.
            Long chatId = messages.findById(messageId).map(Message::getChatId).orElse(null);
            events.messageRead(messageId, chatId, recipientName);
        });
    }

    @Transactional(readOnly = true)
    public List<MessageView> undeliveredMessages(String userId) {
        List<InboxEntry> entries = inboxEntries.findByRecipientUserIdAndDeliveredFalseOrderByCreatedAtAscIdAsc(
                requireUserId(userId));
        if (entries.isEmpty()) {
            return List.of();
        }

        Map<Long, Message> messagesById = messages.findAllById(entries.stream()
                        .map(InboxEntry::getMessageId)
                        .toList())
                .stream()
                .collect(Collectors.toMap(Message::getId, Function.identity()));

        List<MessageView> result = new ArrayList<>();
        for (InboxEntry entry : entries) {
            Message message = messagesById.get(entry.getMessageId());
            if (message != null) {
                result.add(MessageView.from(message));
            }
        }
        return result;
    }

    @Scheduled(cron = "0 0 0 * * *")
    @Transactional
    public void cleanupInbox() {
        inboxEntries.deleteDeliveredOrExpired(Instant.now().minus(30, ChronoUnit.DAYS));
    }

    private ChatView toView(Chat chat, List<ChatParticipant> participantRows) {
        List<String> names = participantRows.stream()
                .map(ChatParticipant::getUsername)
                .sorted()
                .toList();
        return new ChatView(chat.getId(), chat.getName(), names, chat.getCreatedAt());
    }

    private void requireParticipant(String userId, Long chatId) {
        String currentUserId = requireUserId(userId);
        if (chatId == null || !chats.existsById(chatId)) {
            throw new NotFoundException("chat not found");
        }
        if (!participants.existsByChatIdAndUserId(chatId, currentUserId)) {
            throw new ForbiddenException("not a chat participant");
        }
    }

    private List<ChatParticipant> participantRows(Long chatId) {
        return participants.findByChatIdOrderByUserIdAsc(chatId);
    }

    private int clampPageSize(int requestedPageSize) {
        if (requestedPageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(requestedPageSize, MAX_PAGE_SIZE);
    }

    private String encodeCursor(Message message) {
        String raw = message.getCreatedAt() + "|" + message.getId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private MessageCursor decodeCursor(String cursor) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = raw.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid cursor");
            }
            return new MessageCursor(Instant.parse(parts[0]), Long.parseLong(parts[1]));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid cursor");
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private String requireUserId(String value) {
        try { return java.util.UUID.fromString(value).toString(); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("userId must be a UUID"); }
    }

    private UserRef requireUser(UserRef user) {
        if (user == null) throw new IllegalArgumentException("participant is required");
        return new UserRef(requireUserId(user.userId()), requireText(user.username(), "participant username"));
    }

    private String normalizeOptional(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
