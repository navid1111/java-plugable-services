package com.example.media.cloudinary;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CloudinaryClientTest {

    @Test
    void qualifiesDirectUploadIdsExactlyOnce() {
        CloudinaryClient client = new CloudinaryClient("cloud", "key", "secret", "app-media");

        assertEquals("app-media/intent-123", client.qualifyPublicId("intent-123"));
        assertEquals("app-media/intent-123", client.qualifyPublicId("app-media/intent-123"));
    }

    @Test
    void leavesPublicIdUnchangedWhenFolderIsDisabled() {
        CloudinaryClient client = new CloudinaryClient("cloud", "key", "secret", "");

        assertEquals("intent-123", client.qualifyPublicId("intent-123"));
    }
}
