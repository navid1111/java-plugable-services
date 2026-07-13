package com.example.media.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.media.security.JwtHelper;
import com.example.media.service.MediaService;
import com.example.media.service.MediaUploadIntentService;
import com.example.platform.messaging.support.JwtIdentity;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

class MediaControllerTest {

    @Test
    void uploadReturnsForbiddenInsteadOfInternalServerErrorWhenTargetIsUnavailable() {
        MediaService media = mock(MediaService.class);
        JwtHelper jwt = mock(JwtHelper.class);
        MediaUploadIntentService intents = mock(MediaUploadIntentService.class);
        MediaController controller = new MediaController(media, jwt, intents);
        MockMultipartFile file = new MockMultipartFile("file", "asset.png", "image/png", new byte[] {1});
        String userId = "550e8400-e29b-41d4-a716-446655440000";
        when(jwt.extractIdentity("Bearer token")).thenReturn(new JwtIdentity(userId, "alice"));
        when(media.upload(eq(userId), eq("alice"), eq("post"), eq("42"), any(), isNull(), isNull()))
                .thenThrow(new MediaService.ForbiddenException("target does not exist or is deleted"));

        var response = controller.upload("Bearer token", "post", "42", file, null, null);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertTrue(response.getBody() instanceof Map);
        assertEquals("target does not exist or is deleted", ((Map<?, ?>) response.getBody()).get("error"));
    }
}
