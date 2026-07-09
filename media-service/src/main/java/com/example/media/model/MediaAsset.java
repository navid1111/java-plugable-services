package com.example.media.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "media_assets",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_media_assets_provider_id", columnNames = {"resource_type", "public_id"})
        },
        indexes = {
                @Index(name = "idx_media_target_created", columnList = "target_type, target_id, created_at DESC, id DESC"),
                @Index(name = "idx_media_uploader_created", columnList = "uploader_username, created_at DESC, id DESC")
        })
public class MediaAsset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false, length = 100)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 200)
    private String targetId;

    @Column(name = "uploader_username", nullable = false, length = 100)
    private String uploaderUsername;

    @Column(name = "public_id", nullable = false, length = 500)
    private String publicId;

    @Column(name = "resource_type", nullable = false, length = 20)
    private String resourceType;

    @Column(nullable = false, length = 20)
    private String format;

    @Column(name = "secure_url", nullable = false, length = 1000)
    private String secureUrl;

    @Column(name = "thumbnail_url", length = 1000)
    private String thumbnailUrl;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(nullable = false)
    private long bytes;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private Double durationSeconds;

    @Column(length = 500)
    private String caption;

    @Column(name = "alt_text", length = 500)
    private String altText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected MediaAsset() {
        // required by JPA
    }

    public MediaAsset(
            String targetType,
            String targetId,
            String uploaderUsername,
            String publicId,
            String resourceType,
            String format,
            String secureUrl,
            String thumbnailUrl,
            String originalFilename,
            long bytes,
            Integer width,
            Integer height,
            Double durationSeconds,
            String caption,
            String altText) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.uploaderUsername = uploaderUsername;
        this.publicId = publicId;
        this.resourceType = resourceType;
        this.format = format;
        this.secureUrl = secureUrl;
        this.thumbnailUrl = thumbnailUrl;
        this.originalFilename = originalFilename;
        this.bytes = bytes;
        this.width = width;
        this.height = height;
        this.durationSeconds = durationSeconds;
        this.caption = caption;
        this.altText = altText;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public void markDeleted() {
        this.deletedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getUploaderUsername() {
        return uploaderUsername;
    }

    public String getPublicId() {
        return publicId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getFormat() {
        return format;
    }

    public String getSecureUrl() {
        return secureUrl;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public long getBytes() {
        return bytes;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Double getDurationSeconds() {
        return durationSeconds;
    }

    public String getCaption() {
        return caption;
    }

    public String getAltText() {
        return altText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
