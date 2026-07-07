package com.example.whatsapp.websocket;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class SessionRegistry {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessionsByUsername = new ConcurrentHashMap<>();

    public void add(String username, WebSocketSession session) {
        sessionsByUsername.computeIfAbsent(username, ignored -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void remove(String username, WebSocketSession session) {
        Set<WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByUsername.remove(username, sessions);
        }
    }

    public void sendToUser(String username, String payload) {
        Set<WebSocketSession> sessions = sessionsByUsername.get(username);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }

        for (WebSocketSession session : sessions) {
            if (!session.isOpen()) {
                remove(username, session);
                continue;
            }
            try {
                synchronized (session) {
                    session.sendMessage(new TextMessage(payload));
                }
            } catch (IOException e) {
                remove(username, session);
            }
        }
    }
}
