package com.example.whatsapp.websocket;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class SessionRegistry {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByUserId = new ConcurrentHashMap<>();

    public void add(String userId, WebSocketSession session) {
        sessionsByUserId.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void remove(String userId, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUserId.remove(userId, sessions);
        }
    }

    public void sendToUser(String userId, String payload) {
        Set<WebSocketSession> sessions = sessionsByUserId.get(userId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                remove(userId, session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                remove(userId, session);
            }
        }
    }
}
