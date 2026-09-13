package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.*;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.service.InterviewSessionService;
import com.mockinterview.backend.service.PerRequestChatClientFactory;
import com.mockinterview.backend.service.PerRequestChatClientFactory.Provider;
import com.mockinterview.backend.service.ReportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final InterviewSessionService interviewSessionService;
    private final ReportService reportService;
    private final UserRepository userRepository;

    @PostMapping
    public SessionStartResponse startSession(
            @Valid @RequestBody StartSessionRequest request,
            @RequestHeader(value = "X-LLM-Api-Key", required = false, defaultValue = "") String apiKey,
            @RequestHeader(value = "X-LLM-Provider", required = false, defaultValue = "GROQ") String provider,
            @RequestHeader(value = "X-LLM-Model", required = false) String model,
            Authentication auth) {
        return interviewSessionService.startSession(resolveUser(auth), request, apiKey, parseProvider(provider), model);
    }

    @PostMapping("/{id}/answers")
    public AnswerSubmitResponse submitAnswer(
            @PathVariable Long id,
            @Valid @RequestBody SubmitAnswerRequest request,
            @RequestHeader(value = "X-LLM-Api-Key", required = false, defaultValue = "") String apiKey,
            @RequestHeader(value = "X-LLM-Provider", required = false, defaultValue = "GROQ") String provider,
            @RequestHeader(value = "X-LLM-Model", required = false) String model,
            Authentication auth) {
        return interviewSessionService.submitAnswer(resolveUser(auth), id, request, apiKey, parseProvider(provider), model);
    }

    @PostMapping("/{id}/complete")
    public ReportResponse completeSession(
            @PathVariable Long id,
            @RequestBody(required = false) CompleteSessionRequest request,
            @RequestHeader(value = "X-LLM-Api-Key", required = false, defaultValue = "") String apiKey,
            @RequestHeader(value = "X-LLM-Provider", required = false, defaultValue = "GROQ") String provider,
            @RequestHeader(value = "X-LLM-Model", required = false) String model,
            Authentication auth) {
        return reportService.completeSession(resolveUser(auth), id, request, apiKey, parseProvider(provider), model);
    }

    @GetMapping("/{id}/report")
    public ReportResponse getReport(@PathVariable Long id, Authentication auth) {
        return reportService.getReport(resolveUser(auth), id);
    }

    @GetMapping("/{id}")
    public SessionSummaryResponse getSession(@PathVariable Long id, Authentication auth) {
        return interviewSessionService.getSession(resolveUser(auth), id);
    }

    @GetMapping("/{id}/current")
    public SessionResumeResponse getResumeState(@PathVariable Long id, Authentication auth) {
        return interviewSessionService.getResumeState(resolveUser(auth), id);
    }

    @PostMapping("/{id}/sections/next")
    public QuestionResponse startNextSection(@PathVariable Long id, Authentication auth) {
        return interviewSessionService.startNextSection(resolveUser(auth), id);
    }

    @GetMapping
    public List<SessionSummaryResponse> listSessions(Authentication auth) {
        return interviewSessionService.listSessions(resolveUser(auth));
    }

    private Provider parseProvider(String raw) {
        try {
            return Provider.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported X-LLM-Provider: " + raw);
        }
    }

    private User resolveUser(Authentication auth) {
        return userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
    }
}
