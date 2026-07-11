package com.example.platform.messaging.support;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /** Unpublished, due messages in arrival order — the drain candidates for a publisher. */
    List<OutboxMessage> findByPublishedAtIsNullAndAvailableAtLessThanEqualOrderByCreatedAtAsc(
            Instant now, Pageable pageable);

    /** Retention: purge already-published rows older than the cutoff. */
    @Modifying
    @Transactional
    @Query("delete from OutboxMessage m where m.publishedAt is not null and m.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
