package com.example.whatsapp.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.whatsapp.model.InboxEntry;

public interface InboxEntryRepository extends JpaRepository<InboxEntry, Long> {

    Optional<InboxEntry> findByMessageIdAndRecipientUserId(Long messageId, String recipientUserId);

    List<InboxEntry> findByRecipientUserIdAndDeliveredFalseOrderByCreatedAtAscIdAsc(String recipientUserId);

    @Modifying
    @Query("""
            DELETE FROM InboxEntry i
            WHERE i.delivered = true OR i.createdAt < :cutoff
            """)
    int deleteDeliveredOrExpired(@Param("cutoff") Instant cutoff);
}
