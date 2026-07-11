package com.example.platform.messaging.support;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface TargetProjectionRepository
        extends JpaRepository<TargetProjection, TargetProjection.Key> {
    List<TargetProjection> findByTargetTypeAndActiveTrue(String targetType);
}
