package com.example.demo.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;

@RestController
@RequestMapping("/internal/users")
public class InternalUserExportController {
    private final UserRepository users; private final byte[] expectedToken;
    public InternalUserExportController(UserRepository users, @Value("${internal.service.token}") String token) {
        this.users=users; expectedToken=token.getBytes(StandardCharsets.UTF_8);
    }
    public record ExportedUser(long rowId, String userId, String username, boolean active) {
        static ExportedUser from(User u) { return new ExportedUser(u.getId(), u.getUserId().toString(), u.getUsername(), u.isActive()); }
    }
    public record ExportPage(List<ExportedUser> items, long checkpoint, boolean hasMore) {}
    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestHeader(value="X-Internal-Service-Token", required=false) String token,
            @RequestParam(defaultValue="0") long afterId, @RequestParam(defaultValue="500") int pageSize) {
        if (token==null || !MessageDigest.isEqual(expectedToken, token.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        int size=Math.max(1,Math.min(500,pageSize)); var fetched=users.exportAfter(afterId,size+1);
        boolean more=fetched.size()>size; var items=fetched.stream().limit(size).map(ExportedUser::from).toList();
        long checkpoint=items.isEmpty()?afterId:items.getLast().rowId();
        return ResponseEntity.ok(new ExportPage(items,checkpoint,more));
    }
}
