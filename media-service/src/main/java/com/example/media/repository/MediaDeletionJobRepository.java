package com.example.media.repository;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.example.media.model.MediaDeletionJob;

public interface MediaDeletionJobRepository extends JpaRepository<MediaDeletionJob, Long> {
    List<MediaDeletionJob> findTop50ByCompletedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(Instant now);
}
