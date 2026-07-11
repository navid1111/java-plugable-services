package com.example.platform.messaging.support;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, InboxMessage.Key> {

    boolean existsByConsumerAndEventId(String consumer, java.util.UUID eventId);

    /** Retention: purge processed markers older than the cutoff. */
    @Modifying
    @Transactional
    @Query("delete from InboxMessage m where m.processedAt < :cutoff")
    int deleteProcessedBefore(@Param("cutoff") Instant cutoff);
}
