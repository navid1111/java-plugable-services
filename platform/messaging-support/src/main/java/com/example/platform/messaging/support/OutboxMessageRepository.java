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

    /**
     * Atomically claim a batch of due, unpublished rows for the current transaction.
     * {@code FOR UPDATE SKIP LOCKED} lets competing publisher instances drain disjoint
     * rows without blocking each other, so a single row is never published twice. The
     * rows stay locked until the caller's transaction commits, so this must be invoked
     * inside a transaction that spans the publish + mark-published.
     */
    @Query(value = "select * from outbox_messages where published_at is null "
            + "and available_at <= :now order by created_at asc limit :limit "
            + "for update skip locked", nativeQuery = true)
    List<OutboxMessage> claimBatch(@Param("now") Instant now, @Param("limit") int limit);

    long countByPublishedAtIsNull();

    /** Retention: purge already-published rows older than the cutoff. */
    @Modifying
    @Transactional
    @Query("delete from OutboxMessage m where m.publishedAt is not null and m.publishedAt < :cutoff")
    int deletePublishedBefore(@Param("cutoff") Instant cutoff);
}
