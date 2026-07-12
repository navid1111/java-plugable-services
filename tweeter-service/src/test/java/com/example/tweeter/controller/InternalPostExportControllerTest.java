package com.example.tweeter.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.platform.messaging.support.WorkloadJwtIssuer;
import com.example.platform.messaging.support.WorkloadJwtVerifier;
import com.example.tweeter.repository.PostRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class InternalPostExportControllerTest {
    private static final String SECRET = "dev-search-workload-secret-0123456789abcdef";

    @Test
    void directCallRequiresTrustedAudienceScopedWorkload() {
        ObjectMapper mapper = new ObjectMapper();
        PostRepository posts = mock(PostRepository.class);
        when(posts.exportAfter(anyLong(), anyInt())).thenReturn(List.of());
        WorkloadJwtVerifier verifier = new WorkloadJwtVerifier("tweeter-service",
                Map.of("post-search-service", SECRET), Duration.ofMinutes(2), mapper);
        WorkloadJwtIssuer issuer = new WorkloadJwtIssuer("post-search-service", SECRET,
                Duration.ofSeconds(30), mapper);
        InternalPostExportController controller = new InternalPostExportController(posts, verifier);

        assertEquals(HttpStatus.UNAUTHORIZED, controller.export(null, 0, 10).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.export(
                issuer.authorization("auth-service", "posts:export"), 0, 10).getStatusCode());
        assertEquals(HttpStatus.OK, controller.export(
                issuer.authorization("tweeter-service", "posts:export"), 0, 10).getStatusCode());
    }
}
