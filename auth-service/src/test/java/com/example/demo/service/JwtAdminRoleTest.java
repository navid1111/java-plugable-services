package com.example.demo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.demo.model.User;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtAdminRoleTest {

    @Test
    void adminTokenCarriesUserAndAdminAuthorities() {
        User admin = mock(User.class);
        when(admin.getUserId()).thenReturn(UUID.randomUUID());
        when(admin.getUsername()).thenReturn("admin");
        when(admin.isAdmin()).thenReturn(true);
        JwtService jwt = new JwtService(
                "test-secret-that-is-at-least-thirty-two-bytes-long",
                "test-issuer", 60);

        JwtService.Identity identity = jwt.extractIdentity(jwt.issueToken(admin));

        assertThat(identity.roles()).containsExactlyInAnyOrder("USER", "ADMIN");
    }
}
