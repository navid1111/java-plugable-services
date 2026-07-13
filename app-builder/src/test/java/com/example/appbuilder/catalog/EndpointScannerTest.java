package com.example.appbuilder.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EndpointScannerTest {

    @TempDir
    Path repoRoot;

    @Test
    void publishesBrowserRoutesAndHidesWorkloadOnlyControllers() throws Exception {
        Path controllers = Files.createDirectories(repoRoot.resolve(
                "auth-service/src/main/java/com/example/auth/controller"));
        Files.writeString(controllers.resolve("AuthController.java"), """
                @RestController
                @RequestMapping("/auth")
                class AuthController {
                    @PostMapping("/login") void login() {}
                    @GetMapping("/me") void me() {}
                }
                """);
        Files.writeString(controllers.resolve("InternalUserController.java"), """
                @RestController
                @RequestMapping("/internal/users")
                class InternalUserController {
                    @GetMapping("/export") void export() {}
                }
                """);

        EndpointScanner scanner = new EndpointScanner(repoRoot.toString());

        assertThat(scanner.endpointsFor("auth-service"))
                .containsExactly("GET /auth/me", "POST /auth/login")
                .noneMatch(endpoint -> endpoint.contains("/internal/"));
    }
}
