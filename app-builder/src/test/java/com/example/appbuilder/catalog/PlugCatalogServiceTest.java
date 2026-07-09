package com.example.appbuilder.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlugCatalogServiceTest {

    @TempDir
    Path repoRoot;

    @Test
    void discoversPlugKitsWithGatewayPaths() throws Exception {
        writePlugKit(
                "tweeter-service",
                "services:\n  tweeter-service:\n    image: tweeter-service:latest\n",
                "curl --data \"paths[]=/posts\" http://localhost:8001/routes\n",
                "#!/usr/bin/env bash\necho smoke\n");

        PlugCatalogService catalog = new PlugCatalogService(repoRoot);

        assertThat(catalog.listPlugs())
                .containsExactly(
                        new PlugDescriptor(
                                "tweeter-service",
                                "Tweeter Service",
                                "tweeter-service",
                                PlugStatus.AVAILABLE,
                                "tweeter-service/plug/compose.plug.yml",
                                "tweeter-service/plug/kong-setup.sh",
                                "tweeter-service/plug/smoke.sh",
                                java.util.List.of("/posts")));
    }

    @Test
    void marksIncompletePlugKitAsDeveloping() throws Exception {
        Path plugDir = Files.createDirectories(repoRoot.resolve("payments-service/plug"));
        Files.writeString(plugDir.resolve("compose.plug.yml"), "services: {}\n");

        PlugCatalogService catalog = new PlugCatalogService(repoRoot);

        assertThat(catalog.listPlugs()).hasSize(1);
        PlugDescriptor plug = catalog.listPlugs().getFirst();
        assertThat(plug.id()).isEqualTo("payments-service");
        assertThat(plug.status()).isEqualTo(PlugStatus.DEVELOPING);
        assertThat(plug.gatewayPaths()).isEmpty();
    }

    @Test
    void matchesUserRequestToAvailablePlugServicesAndMissingCapabilities() throws Exception {
        writePlugKit(
                "auth-service",
                "services:\n  auth-service:\n    image: auth-service:latest\n",
                "curl --data \"paths[]=/auth\" http://localhost:8001/routes\n",
                "#!/usr/bin/env bash\necho smoke\n");
        writePlugKit(
                "turf-service",
                "services:\n  turf-service:\n    image: turf-service:latest\n",
                "curl --data \"paths[]=/bookings\" http://localhost:8001/routes\n",
                "#!/usr/bin/env bash\necho smoke\n");

        PlugCatalogService catalog = new PlugCatalogService(repoRoot);

        CapabilityAssessment assessment = catalog.assessRequest("make me a login and turf booking app with payments");

        assertThat(assessment.availableServiceIds()).containsExactly("auth-service", "turf-service");
        assertThat(assessment.developingCapabilities()).containsExactly("payments");
    }

    private void writePlugKit(String serviceDir, String compose, String kongSetup, String smoke) throws Exception {
        Path plugDir = Files.createDirectories(repoRoot.resolve(serviceDir).resolve("plug"));
        Files.writeString(plugDir.resolve("compose.plug.yml"), compose);
        Files.writeString(plugDir.resolve("kong-setup.sh"), kongSetup);
        Files.writeString(plugDir.resolve("smoke.sh"), smoke);
    }
}
