package com.example.platform.messaging.support;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.transaction.annotation.Transactional;
import com.example.platform.messaging.target.TargetTypeRegistry;

public class TargetProjectionStore {
    public record AuthoritativeTarget(String id, String owner, long version, boolean active, Instant changedAt) {}
    public record ReconciliationResult(int applied, int tombstoned) {}
    private final TargetProjectionRepository targets;
    public TargetProjectionStore(TargetProjectionRepository targets) { this.targets = targets; }

    @Transactional
    public boolean apply(String type, String id, String owner, long version, boolean active, Instant when) {
        TargetTypeRegistry.requireOwner(type);
        TargetProjection target = targets.findById(new TargetProjection.Key(type, id))
                .orElseGet(() -> new TargetProjection(type, id, owner, 0, false, when));
        boolean changed = target.apply(owner, version, active, when);
        if (changed) targets.save(target);
        return changed;
    }

    @Transactional(readOnly = true)
    public TargetProjection requireActive(String type, String id) {
        TargetTypeRegistry.requireOwner(type);
        return targets.findById(new TargetProjection.Key(type, id))
                .filter(TargetProjection::isActive)
                .orElseThrow(() -> new IllegalArgumentException("target does not exist or is deleted"));
    }

    @Transactional(readOnly = true)
    public TargetProjection requireActiveOwnedBy(String type, String id, String username) {
        TargetProjection target = requireActive(type, id);
        if (username == null || !username.equals(target.getOwnerUsername())) {
            throw new IllegalArgumentException("requester is not the target owner");
        }
        return target;
    }

    @Transactional
    public ReconciliationResult reconcilePosts(List<AuthoritativeTarget> authoritative) {
        int applied = 0;
        for (AuthoritativeTarget target : authoritative) {
            if (apply("post", target.id(), target.owner(), target.version(), target.active(), target.changedAt())) applied++;
        }
        Set<String> known = authoritative.stream().map(AuthoritativeTarget::id).collect(Collectors.toSet());
        int tombstoned = 0;
        for (TargetProjection local : targets.findByTargetTypeAndActiveTrue("post")) {
            if (!known.contains(local.getTargetId())) {
                local.apply(null, local.getAggregateVersion() + 1, false, Instant.now());
                targets.save(local); tombstoned++;
            }
        }
        return new ReconciliationResult(applied, tombstoned);
    }
}
