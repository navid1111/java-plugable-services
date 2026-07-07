package com.example.whatsapp.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    public ChatService(
            ChatRepository chats,
            ChatParticipantRepository participants,
            MessageRepository messages,
            InboxEntryRepository inboxEntries) {
        this.chats = chats;
        this.participants = participants;
        this.messages = messages;
        this.inboxEntries = inboxEntries;
    }

    public record ChatView(Long id, String name, List<String> participants, Instant createdAt) {
    }

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

    public record Delivery(MessageView message, List<String> recipients) {
    }

    private record MessageCursor(Instant createdAt, Long id) {
    }

    @Transactional
    public ChatView createChat(String creatorUsername, String requestedName, List<String> requestedParticipants) {
        String creator = requireText(creatorUsername, "username");
        Set<String> uniqueParticipants = new LinkedHashSet<>();
        uniqueParticipants.add(creator);
        if (requestedParticipants != null) {
            for (String participant : requestedParticipants) {
                uniqueParticipants.add(requireText(participant, "participant username"));
            }
        }

        if (uniqueParticipants.size() < 2) {
            throw new IllegalArgumentException("a chat needs at least two participants");
        }
        if (uniqueParticipants.size() > MAX_PARTICIPANTS) {
            throw new IllegalArgumentException("a chat can have at most 100 participants");
        }

        Chat chat = chats.save(new Chat(normalizeOptional(requestedName)));
        List<ChatParticipant> rows = uniqueParticipants.stream()
                .map(username -> new ChatParticipant(chat.getId(), username))
                .toList();
        participants.saveAll(rows);
        return toView(chat, rows);
    }

    @Transactional(readOnly = true)
    public List<ChatView> findMyChats(String username) {
        String currentUser = requireText(username, "username");
        List<Chat> mine = chats.findByParticipant(currentUser);
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
    public MessagePage findMessages(String username, Long chatId, String cursor, int requestedPageSize) {
        requireParticipant(username, chatId);
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
    public Delivery sendMessage(String senderUsername, Long chatId, String content) {
        String sender = requireText(senderUsername, "sender username");
        requireParticipant(sender, chatId);
        String trimmed = requireText(content, "content");
        if (trimmed.length() > 2000) {
            throw new IllegalArgumentException("content must be 2000 characters or fewer");
        }

        List<String> chatParticipants = participantUsernames(chatId);
        Message message = messages.save(new Message(chatId, sender, trimmed));
        List<String> recipients = chatParticipants.stream()
                .filter(username -> !username.equals(sender))
                .toList();
        inboxEntries.saveAll(recipients.stream()
                .map(recipient -> new InboxEntry(message.getId(), recipient))
                .toList());

        return new Delivery(MessageView.from(message), recipients);
    }

    @Transactional
    public void ack(String username, Long messageId) {
        String recipient = requireText(username, "username");
        if (messageId == null) {
            return;
        }
        Optional<InboxEntry> maybeEntry = inboxEntries.findByMessageIdAndRecipientUsername(messageId, recipient);
        maybeEntry.filter(entry -> !entry.isDelivered()).ifPresent(InboxEntry::markDelivered);
    }

    @Transactional(readOnly = true)
    public List<MessageView> undeliveredMessages(String username) {
        String recipient = requireText(username, "username");
        List<InboxEntry> entries = inboxEntries.findByRecipientUsernameAndDeliveredFalseOrderByCreatedAtAscIdAsc(recipient);
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

    private void requireParticipant(String username, Long chatId) {
        String currentUser = requireText(username, "username");
        if (chatId == null || !chats.existsById(chatId)) {
            throw new NotFoundException("chat not found");
        }
        if (!participants.existsByChatIdAndUsername(chatId, currentUser)) {
            throw new ForbiddenException("not a chat participant");
        }
    }

    private List<String> participantUsernames(Long chatId) {
        return participants.findByChatIdOrderByUsernameAsc(chatId).stream()
                .sorted(Comparator.comparing(ChatParticipant::getUsername))
                .map(ChatParticipant::getUsername)
                .toList();
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
