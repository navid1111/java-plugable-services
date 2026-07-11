package com.example.media.service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.media.cloudinary.CloudinaryClient;
import com.example.media.model.MediaAsset;
import com.example.media.model.MediaUploadIntent;
import com.example.media.repository.MediaAssetRepository;
import com.example.media.repository.MediaUploadIntentRepository;
import com.example.platform.messaging.support.TargetProjectionStore;
import com.example.platform.messaging.support.TransactionalEventWriter;
import com.example.platform.messaging.EventEnvelope;
import com.example.platform.messaging.EventTypes;
import com.example.platform.messaging.media.*;

@Service
public class MediaUploadIntentService {
    private final MediaUploadIntentRepository intents; private final MediaAssetRepository assets;
    private final TargetProjectionStore targets; private final CloudinaryClient cloudinary;
    private final TransactionalEventWriter events;
    private final long maxImageBytes; private final long maxVideoBytes;
    private final Set<String> imageFormats; private final Set<String> videoFormats;
    public MediaUploadIntentService(MediaUploadIntentRepository intents, MediaAssetRepository assets,
            TargetProjectionStore targets, CloudinaryClient cloudinary,
            TransactionalEventWriter events,
            @Value("${MEDIA_MAX_IMAGE_BYTES:10485760}") long maxImageBytes,
            @Value("${MEDIA_MAX_VIDEO_BYTES:104857600}") long maxVideoBytes,
            @Value("${MEDIA_ALLOWED_IMAGE_FORMATS:jpg,jpeg,png,gif,webp}") String imageFormats,
            @Value("${MEDIA_ALLOWED_VIDEO_FORMATS:mp4,mov,webm}") String videoFormats) {
        this.intents=intents; this.assets=assets; this.targets=targets; this.cloudinary=cloudinary;
        this.events=events;
        this.maxImageBytes=maxImageBytes; this.maxVideoBytes=maxVideoBytes;
        this.imageFormats=csv(imageFormats); this.videoFormats=csv(videoFormats);
    }
    public record IntentResult(MediaUploadIntent intent, CloudinaryClient.DirectUploadAuthorization authorization) {}
    public record FinalizeRequest(String publicId, String resourceType, String format, String secureUrl,
            long bytes, Integer width, Integer height, Double durationSeconds, String originalFilename) {}

    @Transactional
    public IntentResult create(String owner, String type, String targetId, String idempotencyKey,
            String resourceType, String format, long requestedBytes) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) throw new IllegalArgumentException("idempotencyKey is required");
        var existing = intents.findByOwnerUsernameAndIdempotencyKey(owner, idempotencyKey.trim());
        if (existing.isPresent()) return result(existing.get());
        targets.requireActiveOwnedBy(type, targetId, owner);
        String resource = resourceType == null ? "" : resourceType.trim().toLowerCase(Locale.ROOT);
        String normalizedFormat = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        long limit = switch (resource) { case "image" -> maxImageBytes; case "video" -> maxVideoBytes;
            default -> throw new IllegalArgumentException("resourceType must be image or video"); };
        Set<String> allowed = "image".equals(resource) ? imageFormats : videoFormats;
        if (!allowed.contains(normalizedFormat)) throw new IllegalArgumentException("unsupported media format");
        if (requestedBytes <= 0 || requestedBytes > limit) throw new IllegalArgumentException("requested upload size exceeds limit");
        UUID id = UUID.randomUUID();
        MediaUploadIntent intent = new MediaUploadIntent(id, owner, type, targetId, idempotencyKey.trim(),
                resource, normalizedFormat, limit, "intent-" + id, Instant.now().plus(Duration.ofMinutes(15)));
        intents.save(intent); return result(intent);
    }

    @Transactional
    public MediaAsset finalizeUpload(UUID intentId, String owner, FinalizeRequest result) {
        MediaUploadIntent intent = intents.findById(intentId).orElseThrow(() -> new IllegalArgumentException("upload intent not found"));
        if (!intent.getOwnerUsername().equals(owner)) throw new IllegalArgumentException("upload intent owner mismatch");
        if (intent.getStatus() == MediaUploadIntent.Status.COMPLETED) return assets.findById(intent.getMediaAssetId()).orElseThrow();
        if (intent.getStatus() != MediaUploadIntent.Status.PENDING || intent.getExpiresAt().isBefore(Instant.now()))
            throw new IllegalArgumentException("upload intent expired");
        if (!intent.getPublicId().equals(result.publicId()) || !intent.getResourceType().equals(result.resourceType())
                || !intent.getFormat().equalsIgnoreCase(result.format())) throw new IllegalArgumentException("upload result does not match intent");
        if (result.bytes() <= 0 || result.bytes() > intent.getMaxBytes()) throw new IllegalArgumentException("uploaded object exceeds size limit");
        URI url = URI.create(result.secureUrl());
        if (!"https".equals(url.getScheme()) || url.getHost() == null || !url.getHost().endsWith("cloudinary.com"))
            throw new IllegalArgumentException("untrusted media URL");
        MediaAsset asset = assets.saveAndFlush(new MediaAsset(intent.getTargetType(), intent.getTargetId(), owner,
                result.publicId(), result.resourceType(), result.format(), result.secureUrl(), result.secureUrl(),
                result.originalFilename(), result.bytes(), result.width(), result.height(), result.durationSeconds(), null, null));
        intent.complete(asset.getId()); intents.save(intent);
        emitCompletedLifecycle(asset);
        return asset;
    }

    @Transactional
    public void failUpload(UUID intentId, String owner, String reasonCode) {
        MediaUploadIntent intent = intents.findById(intentId)
                .orElseThrow(() -> new IllegalArgumentException("upload intent not found"));
        if (!intent.getOwnerUsername().equals(owner)) throw new IllegalArgumentException("upload intent owner mismatch");
        if (intent.getStatus() != MediaUploadIntent.Status.PENDING) return;
        intent.fail(); intents.save(intent);
        write(EventTypes.MEDIA_PROCESSING_FAILED_V1, "media-intent", intent.getId().toString(), 1,
                new MediaProcessingFailed(intent.getId().toString(), boundedCode(reasonCode), Instant.now()));
    }

    private void emitCompletedLifecycle(MediaAsset asset) {
        String id = asset.getId().toString();
        write(EventTypes.MEDIA_UPLOADED_V1, "media", id, 1,
                new MediaUploaded(id, "legacy:" + asset.getUploaderUsername(), asset.getTargetType(),
                        asset.getTargetId(), asset.getResourceType() + "/" + asset.getFormat(),
                        asset.getBytes(), asset.getSecureUrl(), asset.getCreatedAt()));
        write(EventTypes.MEDIA_PROCESSING_COMPLETED_V1, "media", id, 2,
                new MediaProcessingCompleted(id, asset.getFormat(), asset.getWidth(), asset.getHeight(),
                        asset.getDurationSeconds(), Instant.now()));
    }

    private void write(String type, String aggregateType, String id, long version, Object payload) {
        events.write(EventEnvelope.fact(type, 1, "media-service", aggregateType, id, version,
                UUID.randomUUID(), null, null, payload));
    }
    private String boundedCode(String value) {
        String normalized = value == null ? "provider_failure" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.substring(0, Math.min(80, normalized.length()));
    }
    private IntentResult result(MediaUploadIntent intent) {
        return new IntentResult(intent, cloudinary.authorizeDirectUpload(intent.getResourceType(), intent.getPublicId()));
    }
    @Scheduled(fixedDelayString = "${media.intents.expiry-delay:60000}")
    @Transactional public void expireAbandoned() {
        for (MediaUploadIntent intent : intents.findTop100ByStatusAndExpiresAtBefore(MediaUploadIntent.Status.PENDING, Instant.now())) intent.expire();
    }
    private Set<String> csv(String value) { return Stream.of(value.split(",")).map(String::trim)
            .map(v -> v.toLowerCase(Locale.ROOT)).filter(v -> !v.isBlank()).collect(Collectors.toSet()); }
}
