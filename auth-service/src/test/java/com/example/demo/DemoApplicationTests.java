package com.example.demo;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import com.example.platform.messaging.support.OutboxMessageRepository;
import com.example.platform.messaging.EventTypes;

@SpringBootTest
@AutoConfigureMockMvc
class DemoApplicationTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired OutboxMessageRepository outbox;

    @Test void contextLoads() {}

    @Test
    void loginAndMeExposeStableIdAndJwtSubjectUsesIt() throws Exception {
        String username = "user-" + UUID.randomUUID();
        mvc.perform(post("/auth/register").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isCreated());
        String login = mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"secret-pass\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var loginJson = mapper.readTree(login);
        String userId = loginJson.path("userId").asText();
        String token = loginJson.path("access_token").asText();
        assertDoesNotThrow(() -> UUID.fromString(userId));
        var claims = mapper.readTree(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1])));
        assertEquals(userId, claims.path("sub").asText());
        assertEquals(username, claims.path("username").asText());
        mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.userId").value(userId))
                .andExpect(jsonPath("$.username").value(username));
    }

    @Test
    void lifecycleEventsContainIdentityButNeverCredentialsOrTokens() throws Exception {
        outbox.deleteAll();
        String username = "events-" + UUID.randomUUID();
        mvc.perform(post("/auth/register").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"never-publish-me\"}"))
                .andExpect(status().isCreated());
        String login = mvc.perform(post("/auth/login").contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"never-publish-me\"}"))
                .andReturn().getResponse().getContentAsString();
        String token = mapper.readTree(login).path("access_token").asText();
        String renamed = "renamed-" + UUID.randomUUID();
        mvc.perform(put("/auth/profile").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"username\":\"" + renamed + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        assertEquals(3, outbox.count());
        assertTrue(outbox.findAll().stream().map(message -> message.getEventType()).toList()
                .containsAll(java.util.List.of(EventTypes.USER_REGISTERED_V1,
                        EventTypes.USER_PROFILE_UPDATED_V1, EventTypes.USER_DEACTIVATED_V1)));
        String payloads = outbox.findAll().stream().map(message -> message.getPayload()).reduce("", String::concat);
        assertFalse(payloads.contains("never-publish-me"));
        assertFalse(payloads.toLowerCase().contains("password"));
        assertFalse(payloads.contains(token));
    }
}
