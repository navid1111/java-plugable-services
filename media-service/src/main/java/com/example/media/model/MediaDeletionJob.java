package com.example.media.model;

import java.time.Instant;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "media_deletion_jobs")
public class MediaDeletionJob {
    @Id private Long mediaAssetId;
    private String publicId;
    private String resourceType;
    private int attempts;
    private Instant nextAttemptAt;
    private Instant completedAt;
    private String lastError;
    protected MediaDeletionJob() {}
    public MediaDeletionJob(MediaAsset asset, Instant now) {
        mediaAssetId = asset.getId(); publicId = asset.getPublicId(); resourceType = asset.getResourceType();
        nextAttemptAt = now;
    }
    public Long getMediaAssetId() { return mediaAssetId; }
    public String getPublicId() { return publicId; }
    public String getResourceType() { return resourceType; }
    public int getAttempts() { return attempts; }
    public Instant getCompletedAt() { return completedAt; }
    public void complete(Instant now) { completedAt = now; lastError = null; }
    public void retry(Instant at, String error) { attempts++; nextAttemptAt = at; lastError = error; }
}
