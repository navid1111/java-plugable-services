package com.example.media.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.example.media.cloudinary.CloudinaryClient;
import com.example.media.model.MediaUploadIntent;
import com.example.media.repository.MediaAssetRepository;
import com.example.media.repository.MediaUploadIntentRepository;
import com.example.platform.messaging.support.TargetProjectionStore;
import com.example.platform.messaging.support.TransactionalEventWriter;
import com.example.media.model.MediaAsset;

class MediaUploadIntentServiceTest {
    private static final String ALICE_ID = "550e8400-e29b-41d4-a716-446655440000";
    @Test
    void repeatedIdempotencyKeyReturnsSameIntentAndAbandonedIntentExpires() {
        var intents = mock(MediaUploadIntentRepository.class); var assets = mock(MediaAssetRepository.class);
        var targets = mock(TargetProjectionStore.class); var cloudinary = mock(CloudinaryClient.class);
        when(cloudinary.qualifyPublicId(anyString())).thenAnswer(call -> "uploads/" + call.getArgument(0));
        when(cloudinary.authorizeDirectUpload(anyString(), anyString()))
                .thenReturn(new CloudinaryClient.DirectUploadAuthorization("https://api.cloudinary.com/upload", "key", 1, "sig", "uploads/id", ""));
        MediaUploadIntentService service = service(intents, assets, targets, cloudinary);
        var first = service.create(ALICE_ID, "alice", "post", "42", "request-1", "image", "png", 1000).intent();
        assertTrue(first.getPublicId().startsWith("uploads/intent-"));
        when(intents.findByOwnerUserIdAndIdempotencyKey(ALICE_ID, "request-1")).thenReturn(Optional.of(first));
        var repeated = service.create(ALICE_ID, "alice-renamed", "post", "42", "request-1", "image", "png", 1000).intent();
        assertEquals(first.getId(), repeated.getId());
        verify(targets, times(1)).requireActiveOwnedBy("post", "42", ALICE_ID);
        verify(cloudinary, times(2)).authorizeDirectUpload("image", first.getPublicId());

        MediaUploadIntent expired = new MediaUploadIntent(UUID.randomUUID(), ALICE_ID, "alice", "post", "42", "old",
                "image", "png", 1000, "old-id", Instant.now().minusSeconds(1));
        when(intents.findTop100ByStatusAndExpiresAtBefore(eq(MediaUploadIntent.Status.PENDING), any()))
                .thenReturn(List.of(expired));
        service.expireAbandoned();
        assertEquals(MediaUploadIntent.Status.EXPIRED, expired.getStatus());
    }

    @Test
    void forgedIdentityAndOversizedFinalizeAreRejectedBeforePersistence() {
        var intents = mock(MediaUploadIntentRepository.class); var assets = mock(MediaAssetRepository.class);
        var target = mock(TargetProjectionStore.class); var cloud = mock(CloudinaryClient.class);
        MediaUploadIntent intent = new MediaUploadIntent(UUID.randomUUID(), ALICE_ID, "alice", "post", "42", "key",
                "image", "png", 1000, "issued-id", Instant.now().plusSeconds(60));
        when(intents.findById(intent.getId())).thenReturn(Optional.of(intent));
        MediaUploadIntentService service = service(intents, assets, target, cloud);
        assertThrows(IllegalArgumentException.class, () -> service.finalizeUpload(intent.getId(), ALICE_ID,
                new MediaUploadIntentService.FinalizeRequest("forged", "image", "png",
                        "https://res.cloudinary.com/x.png", 100, null, null, null, "x.png")));
        assertThrows(IllegalArgumentException.class, () -> service.finalizeUpload(intent.getId(), ALICE_ID,
                new MediaUploadIntentService.FinalizeRequest("issued-id", "image", "png",
                        "https://res.cloudinary.com/x.png", 1001, null, null, null, "x.png")));
        verifyNoInteractions(assets);
    }

    @Test
    void successfulAndFailedLifecyclesWriteTransactionalEvents() {
        var intents = mock(MediaUploadIntentRepository.class); var assets = mock(MediaAssetRepository.class);
        var target = mock(TargetProjectionStore.class); var cloud = mock(CloudinaryClient.class);
        var events = mock(TransactionalEventWriter.class);
        MediaUploadIntent intent = new MediaUploadIntent(UUID.randomUUID(), ALICE_ID, "alice", "post", "42", "key",
                "image", "png", 1000, "issued-id", Instant.now().plusSeconds(60));
        when(intents.findById(intent.getId())).thenReturn(Optional.of(intent));
        MediaAsset saved = mock(MediaAsset.class);
        when(saved.getId()).thenReturn(9L); when(saved.getUploaderUsername()).thenReturn("alice");
        when(saved.getUploaderUserId()).thenReturn(ALICE_ID);
        when(saved.getTargetType()).thenReturn("post"); when(saved.getTargetId()).thenReturn("42");
        when(saved.getResourceType()).thenReturn("image"); when(saved.getFormat()).thenReturn("png");
        when(saved.getBytes()).thenReturn(100L); when(saved.getSecureUrl()).thenReturn("https://res.cloudinary.com/x.png");
        when(saved.getCreatedAt()).thenReturn(Instant.now());
        when(assets.saveAndFlush(any())).thenReturn(saved);
        MediaUploadIntentService service = new MediaUploadIntentService(intents, assets, target, cloud, events,
                10_000, 100_000, "png", "mp4");
        service.finalizeUpload(intent.getId(), ALICE_ID, new MediaUploadIntentService.FinalizeRequest(
                "issued-id", "image", "png", "https://res.cloudinary.com/x.png", 100,
                null, null, null, "x.png"));
        verify(events, times(2)).write(any());

        MediaUploadIntent failed = new MediaUploadIntent(UUID.randomUUID(), ALICE_ID, "alice", "post", "42", "key2",
                "image", "png", 1000, "failed-id", Instant.now().plusSeconds(60));
        when(intents.findById(failed.getId())).thenReturn(Optional.of(failed));
        service.failUpload(failed.getId(), ALICE_ID, "provider unavailable");
        assertEquals(MediaUploadIntent.Status.FAILED, failed.getStatus());
        verify(events, times(3)).write(any());
    }

    private MediaUploadIntentService service(MediaUploadIntentRepository intents, MediaAssetRepository assets,
            TargetProjectionStore targets, CloudinaryClient cloudinary) {
        return new MediaUploadIntentService(intents, assets, targets, cloudinary,
                mock(TransactionalEventWriter.class), 10_000, 100_000,
                "jpg,jpeg,png,webp", "mp4,webm");
    }
}
