package com.example.media.model;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "media_upload_intents", uniqueConstraints =
        @UniqueConstraint(name = "uk_media_intent_owner_key", columnNames = {"owner_user_id", "idempotency_key"}))
public class MediaUploadIntent {
    public enum Status { PENDING, COMPLETED, FAILED, EXPIRED }
    @Id private UUID id;
    private String ownerUsername;
    private String ownerUserId;
    private String targetType;
    private String targetId;
    private String idempotencyKey;
    private String resourceType;
    private String format;
    private long maxBytes;
    private String publicId;
    private Instant expiresAt;
    @Enumerated(EnumType.STRING) private Status status;
    private Long mediaAssetId;
    protected MediaUploadIntent() {}
    public MediaUploadIntent(UUID id, String ownerUserId, String owner, String type, String targetId, String key,
            String resourceType, String format, long maxBytes, String publicId, Instant expiresAt) {
        this.id=id; this.ownerUserId=ownerUserId; ownerUsername=owner; targetType=type;
        this.targetId=targetId; idempotencyKey=key;
        this.resourceType=resourceType; this.format=format; this.maxBytes=maxBytes;
        this.publicId=publicId; this.expiresAt=expiresAt; status=Status.PENDING;
    }
    public String getOwnerUserId(){return ownerUserId;}
    public UUID getId(){return id;} public String getOwnerUsername(){return ownerUsername;}
    public String getTargetType(){return targetType;} public String getTargetId(){return targetId;}
    public String getResourceType(){return resourceType;} public String getFormat(){return format;}
    public long getMaxBytes(){return maxBytes;} public String getPublicId(){return publicId;}
    public Instant getExpiresAt(){return expiresAt;} public Status getStatus(){return status;}
    public Long getMediaAssetId(){return mediaAssetId;}
    public void complete(Long assetId){status=Status.COMPLETED; mediaAssetId=assetId;}
    public void expire(){if(status==Status.PENDING) status=Status.EXPIRED;}
    public void fail(){if(status==Status.PENDING) status=Status.FAILED;}
}
