package com.example.media.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.example.media.cloudinary.CloudinaryClient;
import com.example.media.model.MediaAsset;
import com.example.media.model.MediaDeletionJob;
import com.example.media.repository.MediaAssetRepository;
import com.example.media.repository.MediaDeletionJobRepository;

class MediaDeletionServiceTest {
    @Test
    void providerFailureThenRecoveryConvergesAndDuplicateEnqueueIsHarmless() {
        MediaAssetRepository assets = mock(MediaAssetRepository.class);
        MediaDeletionJobRepository jobs = mock(MediaDeletionJobRepository.class);
        CloudinaryClient cloudinary = mock(CloudinaryClient.class);
        MediaAsset asset = mock(MediaAsset.class);
        when(asset.getId()).thenReturn(7L);
        when(asset.getPublicId()).thenReturn("cloud-id");
        when(asset.getResourceType()).thenReturn("image");
        when(asset.getDeletedAt()).thenReturn(Instant.now());
        when(jobs.existsById(7L)).thenReturn(false, true);

        MediaDeletionService service = new MediaDeletionService(assets, jobs, cloudinary);
        service.enqueue(asset);
        service.enqueue(asset);
        verify(jobs, times(1)).save(any(MediaDeletionJob.class));

        MediaDeletionJob job = new MediaDeletionJob(asset, Instant.now());
        when(jobs.findTop50ByCompletedAtIsNullAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(any()))
                .thenReturn(List.of(job));
        doThrow(new RuntimeException("provider unavailable")).doNothing()
                .when(cloudinary).destroy("cloud-id", "image");
        service.drain();
        assertNull(job.getCompletedAt());
        assertEquals(1, job.getAttempts());
        service.drain();
        assertNotNull(job.getCompletedAt());
    }
}
