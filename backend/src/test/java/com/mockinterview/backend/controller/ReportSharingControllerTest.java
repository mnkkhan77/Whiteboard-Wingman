package com.mockinterview.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.repository.InterviewSessionRepository;
import com.mockinterview.backend.repository.ReportRepository;
import com.mockinterview.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack tests for the report-sharing endpoints, through the real security filter chain and
 * an in-memory H2 database — see SessionControllerTest for the same style. Sessions/reports are
 * built directly through the repositories rather than by playing an interview end to end, since
 * only the sharing behavior (ownership, idempotency, public visibility) is under test here.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReportSharingControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private InterviewSessionRepository sessionRepository;
    @Autowired private ReportRepository reportRepository;

    private record Registered(String token, User user) {}

    private Registered registerAndGetUser() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email, "password", "password123", "displayName", "Test User"));

        String response = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String token = objectMapper.readTree(response).get("token").asText();
        User user = userRepository.findByEmail(email).orElseThrow();
        return new Registered(token, user);
    }

    private Report completedReportFor(User user) {
        InterviewSession session = new InterviewSession();
        session.setUser(user);
        session.setTopic(Topic.DSA);
        session.setStartingDifficulty(Difficulty.EASY);
        session.setCurrentDifficulty(Difficulty.EASY);
        session.setStatus(SessionStatus.COMPLETED);
        session.setTargetQuestionCount(1);
        session.setQuestionsAsked(1);
        session = sessionRepository.save(session);

        Report report = new Report();
        report.setSession(session);
        report.setOverallScore(80);
        report.setQuestionCount(1);
        report.setAverageDifficultyReached(0);
        return reportRepository.save(report);
    }

    private String shareToken(String authToken, Long sessionId) throws Exception {
        String response = mockMvc.perform(post("/api/sessions/" + sessionId + "/report/share")
                        .header("Authorization", "Bearer " + authToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("shareToken").asText();
    }

    @Test
    void shareReportGeneratesATokenAndIsIdempotent() throws Exception {
        Registered owner = registerAndGetUser();
        Report report = completedReportFor(owner.user());

        String first = shareToken(owner.token(), report.getSession().getId());
        String second = shareToken(owner.token(), report.getSession().getId());

        assertThat(first).isNotBlank();
        assertThat(first).isEqualTo(second);
    }

    @Test
    void shareReportIsRejectedForAUserWhoDoesNotOwnTheSession() throws Exception {
        Registered owner = registerAndGetUser();
        Registered other = registerAndGetUser();
        Report report = completedReportFor(owner.user());

        mockMvc.perform(post("/api/sessions/" + report.getSession().getId() + "/report/share")
                        .header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shareReportRequiresAuthentication() throws Exception {
        Registered owner = registerAndGetUser();
        Report report = completedReportFor(owner.user());

        mockMvc.perform(post("/api/sessions/" + report.getSession().getId() + "/report/share"))
                .andExpect(status().isForbidden());
    }

    @Test
    void publicEndpointReturnsReportDataForAValidTokenWithoutAuth() throws Exception {
        Registered owner = registerAndGetUser();
        Report report = completedReportFor(owner.user());
        String token = shareToken(owner.token(), report.getSession().getId());

        mockMvc.perform(get("/api/public/reports/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallScore").value(80))
                .andExpect(jsonPath("$.sessionId").value(report.getSession().getId()));
    }

    @Test
    void publicEndpointReturns404ForAnUnknownToken() throws Exception {
        mockMvc.perform(get("/api/public/reports/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
