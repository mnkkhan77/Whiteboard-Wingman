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
 * Full-stack tests through the real Spring Security filter chain and an in-memory H2 database
 * (application-test.yml) — no LLM calls are exercised here, only auth/RBAC/validation, which is
 * everything reachable before an evaluation call would be made.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionControllerTest {

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
    void unauthenticatedRequestToAProtectedEndpointIsRejected() throws Exception {
        mockMvc.perform(get("/api/sessions"))
                .andExpect(status().isForbidden());
    }

    @Test
    void registerReturnsAUsableJwt() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/api/sessions")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void registeringTheSameEmailTwiceIsRejectedWithACleanBadRequest() throws Exception {
        String email = "dup-" + UUID.randomUUID() + "@example.com";
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email, "password", "password123", "displayName", "Test User"));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void startingASessionWithoutTheLlmApiKeyHeaderStartsAKeylessSessionOnATopicWithBankContent() throws Exception {
        // DSA's static question bank has MCQ and CODING entries, so a keyless session (no
        // X-LLM-Api-Key header at all) can still run those two sections — see
        // InterviewSessionService.startSession/sectionOrder.
        String token = registerAndGetToken();
        String body = objectMapper.writeValueAsString(Map.of(
                "topic", "DSA", "startingDifficulty", "EASY", "questionCount", 3));

        mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void malformedRequestBodyReturnsACleanBadRequestNotAnUnhandled500() throws Exception {
        // Regression test: GlobalExceptionHandler's catch-all Exception handler (added for
        // unexpected bugs) would otherwise shadow Spring's own HttpMessageNotReadableException
        // handling and turn this into a 500 — same class of bug as the /error-dispatch 403 fix.
        String token = registerAndGetToken();

        mockMvc.perform(post("/api/sessions")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not valid json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminEndpointIsRejectedForAnOrdinaryUser() throws Exception {
        String token = registerAndGetToken();

        mockMvc.perform(get("/api/admin/anything")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
