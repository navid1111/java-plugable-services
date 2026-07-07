package com.example.whatsapp.websocket;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import com.example.whatsapp.service.ChatService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private final SessionRegistry sessions;
    private final ChatService chats;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(SessionRegistry sessions, ChatService chats, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.chats = chats;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String username = username(session);
        sessions.add(username, session);
        for (ChatService.MessageView message : chats.undeliveredMessages(username)) {
            session.sendMessage(new TextMessage(event("newMessage", Map.of("message", message))));
        }
    }

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String username = username(session);
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = requiredText(root, "type");
            if ("sendMessage".equals(type)) {
                Long chatId = requiredLong(root, "chatId");
                String content = requiredText(root, "content");
                ChatService.Delivery delivery = chats.sendMessage(username, chatId, content);
                String payload = event("newMessage", Map.of("message", delivery.message()));
                for (String recipient : delivery.recipients()) {
                    sessions.sendToUser(recipient, payload);
                }
                session.sendMessage(new TextMessage(event("messageSent", Map.of("message", delivery.message()))));
            } else if ("ack".equals(type)) {
                chats.ack(username, requiredLong(root, "messageId"));
                session.sendMessage(new TextMessage(event("ack", Map.of("messageId", requiredLong(root, "messageId")))));
            } else {
                session.sendMessage(new TextMessage(event("error", Map.of("message", "unknown message type"))));
            }
        } catch (ChatService.ForbiddenException e) {
            session.sendMessage(new TextMessage(event("error", Map.of("message", e.getMessage()))));
        } catch (ChatService.NotFoundException e) {
            session.sendMessage(new TextMessage(event("error", Map.of("message", e.getMessage()))));
        } catch (IllegalArgumentException e) {
            session.sendMessage(new TextMessage(event("error", Map.of("message", e.getMessage()))));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object username = session.getAttributes().get("username");
        if (username instanceof String value) {
            sessions.remove(value, session);
        }
    }

    private String username(WebSocketSession session) {
        Object username = session.getAttributes().get("username");
        if (username instanceof String value && !value.isBlank()) {
            return value;
        }
        throw new IllegalArgumentException("missing websocket username");
    }

    private String event(String type, Map<String, ?> data) throws Exception {
        return objectMapper.writeValueAsString(Map.of("type", type, "data", data));
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asText();
    }

    private Long requiredLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.asLong();
    }
}
