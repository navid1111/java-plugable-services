package com.example.platform.messaging.support;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TargetProjectionRepository
        extends JpaRepository<TargetProjection, TargetProjection.Key> {}
