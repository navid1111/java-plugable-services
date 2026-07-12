package com.example.media.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.media.model.MediaUploadIntent;

public interface MediaUploadIntentRepository extends JpaRepository<MediaUploadIntent, UUID> {
    Optional<MediaUploadIntent> findByOwnerUserIdAndIdempotencyKey(String ownerUserId, String key);
    List<MediaUploadIntent> findTop100ByStatusAndExpiresAtBefore(
            MediaUploadIntent.Status status, Instant now);
}
