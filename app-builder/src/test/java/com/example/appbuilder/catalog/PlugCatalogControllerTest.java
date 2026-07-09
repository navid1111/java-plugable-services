package com.example.appbuilder.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlugCatalogControllerTest {

    @TempDir
    Path repoRoot;

    @Test
    void listsDiscoveredPlugServicesOverHttp() throws Exception {
        Path plugDir = Files.createDirectories(repoRoot.resolve("auth-service/plug"));
        Files.writeString(plugDir.resolve("compose.plug.yml"), "services: {}\n");
        Files.writeString(plugDir.resolve("kong-setup.sh"), "curl --data \"paths[]=/auth\"\n");
        Files.writeString(plugDir.resolve("smoke.sh"), "#!/usr/bin/env bash\n");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlugCatalogController(
                new PlugCatalogService(repoRoot), new EndpointScanner(repoRoot.toString()))).build();

        mvc.perform(get("/api/plugs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("auth-service"))
                .andExpect(jsonPath("$[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$[0].gatewayPaths[0]").value("/auth"));
    }
}
