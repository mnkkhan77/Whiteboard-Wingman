package com.mockinterview.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack tests through the real Spring Security filter chain, same style as
 * SessionControllerTest — no LLM calls are exercised, only auth/validation, since
 * PerRequestChatClientFactory rejects a missing API key before any provider call is made.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TopicControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String registerAndGetToken() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email, "password", "password123", "displayName", "Test User"));

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(response).get("token").asText();
    }

    @Test
    void unauthenticatedRecommendRequestIsRejected() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("resumeOrJdText", "Java backend engineer"));

        mockMvc.perform(post("/api/topics/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    @Test
    void recommendWithoutAnLlmApiKeyIsRejectedWithACleanBadRequest() throws Exception {
        String token = registerAndGetToken();
        String body = objectMapper.writeValueAsString(Map.of("resumeOrJdText", "Java backend engineer"));

        mockMvc.perform(post("/api/topics/recommend")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Missing X-LLM-Api-Key header"));
    }

    @Test
    void recommendWithBlankResumeTextIsRejectedByValidation() throws Exception {
        String token = registerAndGetToken();
        String body = objectMapper.writeValueAsString(Map.of("resumeOrJdText", "   "));

        mockMvc.perform(post("/api/topics/recommend")
                        .header("Authorization", "Bearer " + token)
                        .header("X-LLM-Api-Key", "fake-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
