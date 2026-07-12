package com.example.demo.controller;

import java.util.List;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;
import com.example.platform.messaging.support.WorkloadAuthenticationException;
import com.example.platform.messaging.support.WorkloadJwtVerifier;

@RestController
@RequestMapping("/internal/users")
public class InternalUserExportController {
    private final UserRepository users; private final WorkloadJwtVerifier workloads;
    public InternalUserExportController(UserRepository users, WorkloadJwtVerifier workloads) {
        this.users=users; this.workloads=workloads;
    }
    public record ExportedUser(long rowId, String userId, String username, boolean active) {
        static ExportedUser from(User u) { return new ExportedUser(u.getId(), u.getUserId().toString(), u.getUsername(), u.isActive()); }
    }
    public record ExportPage(List<ExportedUser> items, long checkpoint, boolean hasMore) {}
    @GetMapping("/export")
    public ResponseEntity<?> export(@RequestHeader(value=HttpHeaders.AUTHORIZATION, required=false) String authorization,
            @RequestParam(defaultValue="0") long afterId, @RequestParam(defaultValue="500") int pageSize) {
        try { workloads.verify(authorization, "identity:export"); }
        catch (WorkloadAuthenticationException denied) { return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build(); }
        int size=Math.max(1,Math.min(500,pageSize)); var fetched=users.exportAfter(afterId,size+1);
        boolean more=fetched.size()>size; var items=fetched.stream().limit(size).map(ExportedUser::from).toList();
        long checkpoint=items.isEmpty()?afterId:items.getLast().rowId();
        return ResponseEntity.ok(new ExportPage(items,checkpoint,more));
    }
}
