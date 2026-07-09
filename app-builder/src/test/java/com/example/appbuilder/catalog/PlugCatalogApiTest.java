package com.example.appbuilder.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlugCatalogApiTest {

    @TempDir
    Path repoRoot;

    @Test
    void assessesPromptAgainstAvailableAndDevelopingCapabilities() throws Exception {
        writePlugKit("auth-service", "/auth");
        writePlugKit("media-service", "/media");

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlugCatalogController(new PlugCatalogService(repoRoot))).build();

        mvc.perform(post("/api/assess")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prompt\":\"make a login app with media uploads and payments\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableServiceIds[0]").value("auth-service"))
                .andExpect(jsonPath("$.availableServiceIds[1]").value("media-service"))
                .andExpect(jsonPath("$.developingCapabilities[0]").value("payments"));
    }

    private void writePlugKit(String serviceDir, String gatewayPath) throws Exception {
        Path plugDir = Files.createDirectories(repoRoot.resolve(serviceDir).resolve("plug"));
        Files.writeString(plugDir.resolve("compose.plug.yml"), "services: {}\n");
        Files.writeString(plugDir.resolve("kong-setup.sh"), "curl --data \"paths[]=" + gatewayPath + "\"\n");
        Files.writeString(plugDir.resolve("smoke.sh"), "#!/usr/bin/env bash\n");
    }
}
