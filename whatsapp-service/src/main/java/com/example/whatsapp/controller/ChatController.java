package com.example.whatsapp.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.whatsapp.security.JwtHelper;
import com.example.whatsapp.service.ChatService;

@RestController
@RequestMapping("/chat")
public class ChatController {

    private final ChatService chats;
    private final JwtHelper jwtHelper;

    public ChatController(ChatService chats, JwtHelper jwtHelper) {
        this.chats = chats;
        this.jwtHelper = jwtHelper;
    }

    public record ParticipantRequest(String userId, String username) {}
    public record CreateChatRequest(String name, List<ParticipantRequest> participants) {
    }

    @PostMapping("/chats")
    public ResponseEntity<?> createChat(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody CreateChatRequest request) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            List<ChatService.UserRef> participants = request.participants() == null ? List.of()
                    : request.participants().stream()
                            .map(p -> new ChatService.UserRef(p.userId(), p.username())).toList();
            ChatService.ChatView chat = chats.createChat(identity.userId(), identity.username(),
                    request.name(), participants);
            return ResponseEntity.status(HttpStatus.CREATED).body(chat);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/chats")
    public ResponseEntity<?> myChats(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            return ResponseEntity.ok(chats.findMyChats(identity.userId()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GetMapping("/chats/{id}/messages")
    public ResponseEntity<?> messages(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int pageSize) {
        try {
            var identity = jwtHelper.extractIdentity(authorization);
            return ResponseEntity.ok(chats.findMessages(identity.userId(), id, cursor, pageSize));
        } catch (ChatService.ForbiddenException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (ChatService.NotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", message));
    }
}
