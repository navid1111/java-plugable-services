package com.example.media.service;

import java.time.Duration;
import java.time.Instant;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.media.cloudinary.CloudinaryClient;
import com.example.media.model.MediaAsset;
import com.example.media.model.MediaDeletionJob;
import com.example.media.repository.MediaAssetRepository;
import com.example.media.repository.MediaDeletionJobRepository;

@Service
public class MediaDeletionService {
    private final MediaAssetRepository assets;
    private final MediaDeletionJobRepository jobs;
    private final CloudinaryClient cloudinary;
    public MediaDeletionService(MediaAssetRepository assets, MediaDeletionJobRepository jobs,
            CloudinaryClient cloudinary) {
        this.assets = assets; this.jobs = jobs; this.cloudinary = cloudinary;
    }

    @Transactional
    public void enqueue(MediaAsset asset) {
        if (asset.getDeletedAt() == null) asset.markDeleted();
        assets.save(asset);
        if (!jobs.existsById(asset.getId())) jobs.save(new MediaDeletionJob(asset, Instant.now()));
    }

    @Transactional
    public void targetChanged(String type, String id, long version, boolean active, Instant when) {
        if (active) return;
        for (MediaAsset asset : assets.findByTargetTypeAndTargetIdAndDeletedAtIsNull(type, id)) enqueue(asset);
    }

    @Scheduled(fixedDelayString = "${media.cleanup.poll-delay:5000}")
    @Transactional
    public void drain() {
        for (MediaDeletionJob job : jobs
                .findTop50ByCompletedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(Instant.now())) {
            try {
                cloudinary.destroy(job.getPublicId(), job.getResourceType());
                job.complete(Instant.now());
            } catch (RuntimeException failure) {
                long seconds = Math.min(3600, 1L << Math.min(job.getAttempts(), 11));
                String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
                job.retry(Instant.now().plus(Duration.ofSeconds(seconds)),
                        message.substring(0, Math.min(500, message.length())));
            }
        }
    }

    /** Repairs assets tombstoned without a durable cleanup job after an interrupted deployment. */
    @Transactional
    public int reconcile() {
        int created = 0;
        for (MediaAsset asset : assets.findAll()) {
            if (asset.getDeletedAt() != null && !jobs.existsById(asset.getId())) {
                jobs.save(new MediaDeletionJob(asset, Instant.now())); created++;
            }
        }
        return created;
    }
}
