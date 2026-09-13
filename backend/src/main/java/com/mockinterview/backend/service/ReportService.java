package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.CompleteSessionRequest;
import com.mockinterview.backend.dto.InterviewReportSummary;
import com.mockinterview.backend.dto.QuestionBreakdown;
import com.mockinterview.backend.dto.ReportResponse;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.repository.EvaluationRepository;
import com.mockinterview.backend.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Phase 1: a simple average + heuristic strong/weak topic split.
 * Phase 4: on first generation, also asks the LLM for a narrative summary and a subtopic-level
 * read of strengths/weaknesses (Report.summaryText, refined strong/weak topics).
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final InterviewSessionService sessionService;
    private final EvaluationRepository evaluationRepository;
    private final ReportRepository reportRepository;
    private final PerRequestChatClientFactory chatClientFactory;

    @Transactional
    public ReportResponse completeSession(User user, Long sessionId, CompleteSessionRequest request, String apiKey,
                                           PerRequestChatClientFactory.Provider provider, String model) {
        InterviewSession session = sessionService.getOwnedSession(user, sessionId);
        if (session.getStatus() != SessionStatus.COMPLETED) {
            throw new IllegalStateException("Session is not yet completed — answer all questions first");
        }

        return reportRepository.findBySession(session)
                .map(existing -> toResponse(session, existing, loadEvaluations(session)))
                .orElseGet(() -> generateAndPersist(session, request, apiKey, provider, model));
    }

    @Transactional(readOnly = true)
    public ReportResponse getReport(User user, Long sessionId) {
        InterviewSession session = sessionService.getOwnedSession(user, sessionId);
        Report report = reportRepository.findBySession(session)
                .orElseThrow(() -> new IllegalStateException("Report not generated yet — call POST /sessions/{id}/complete first"));
        return toResponse(session, report, loadEvaluations(session));
    }

    private ReportResponse generateAndPersist(InterviewSession session, CompleteSessionRequest request, String apiKey,
                                               PerRequestChatClientFactory.Provider provider, String model) {
        if (request != null && request.tabSwitchCount() != null) {
            session.setTabSwitchCount(Math.max(0, request.tabSwitchCount()));
        }

        List<Evaluation> evaluations = loadEvaluations(session);

        int overallScore = (int) Math.round(evaluations.stream().mapToInt(Evaluation::getScore).average().orElse(0));
        double averageDifficulty = evaluations.stream()
                .mapToInt(e -> difficultyOrdinal(e.getAnswer().getQuestion().getDifficulty()))
                .average().orElse(0);

        Report report = new Report();
        report.setSession(session);
        report.setOverallScore(overallScore);
        report.setQuestionCount(evaluations.size());
        report.setAverageDifficultyReached(averageDifficulty);

        String topicName = session.getTopic().name();
        if (overallScore >= 70) {
            report.setStrongTopics(List.of(topicName));
        } else if (overallScore <= 40) {
            report.setWeakTopics(List.of(topicName));
        }

        if (session.isLlmAvailable()) {
            ChatClient chatClient = chatClientFactory.forRequest(apiKey, provider, model);
            log.info("Generating narrative report: sessionId={}, provider={}, model={}", session.getId(), provider, model);
            InterviewReportSummary summary = generateNarrative(chatClient, session, evaluations, overallScore);
            report.setSummaryText(summary.narrativeSummary());
            if (!summary.strongTopics().isEmpty()) {
                report.setStrongTopics(summary.strongTopics());
            }
            if (!summary.weakTopics().isEmpty()) {
                report.setWeakTopics(summary.weakTopics());
            }
        } else {
            log.info("Skipping narrative report generation — session has no LLM key: sessionId={}", session.getId());
        }

        reportRepository.save(report);
        return toResponse(session, report, evaluations);
    }

    private InterviewReportSummary generateNarrative(ChatClient chatClient, InterviewSession session,
                                                       List<Evaluation> evaluations, int overallScore) {
        String breakdown = evaluations.stream()
                .map(e -> "Q%d (%s difficulty, score %d/100, %s): %s | Strengths: %s | Weaknesses: %s".formatted(
                        e.getAnswer().getQuestion().getSequenceNumber(),
                        e.getAnswer().getQuestion().getDifficulty(),
                        e.getScore(),
                        e.getCorrectness(),
                        e.getFeedback(),
                        String.join("; ", e.getStrengths()),
                        String.join("; ", e.getWeaknesses())))
                .collect(Collectors.joining("\n"));

        String system = """
                You are summarizing a completed mock technical interview for the candidate.
                Return your summary using the required structured format only.
                """;

        String user = """
                Topic: %s
                Overall score: %d/100 across %d questions.

                Per-question breakdown:
                %s

                Based on the breakdown above (not just the overall topic name), identify the specific
                subtopics the candidate was strongest and weakest in, and write a concise, encouraging
                but honest 3-5 sentence narrative summary of their performance.
                """.formatted(session.getTopic(), overallScore, evaluations.size(), breakdown);

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(InterviewReportSummary.class);
    }

    private List<Evaluation> loadEvaluations(InterviewSession session) {
        return evaluationRepository.findBySessionOrderByQuestionSequence(session);
    }

    private ReportResponse toResponse(InterviewSession session, Report report, List<Evaluation> evaluations) {
        List<QuestionBreakdown> breakdown = evaluations.stream()
                .map(e -> new QuestionBreakdown(
                        e.getAnswer().getQuestion().getSequenceNumber(),
                        e.getAnswer().getQuestion().getPromptText(),
                        e.getAnswer().getQuestion().getDifficulty(),
                        e.getAnswer().getAnswerText(),
                        e.getScore(),
                        e.getCorrectness(),
                        e.getFeedback()
                ))
                .toList();

        return new ReportResponse(
                session.getId(),
                session.getTopic(),
                report.getOverallScore(),
                List.copyOf(report.getStrongTopics()),
                List.copyOf(report.getWeakTopics()),
                report.getSummaryText(),
                report.getQuestionCount(),
                report.getAverageDifficultyReached(),
                breakdown,
                session.getTabSwitchCount()
        );
    }

    private int difficultyOrdinal(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 0;
            case MEDIUM -> 1;
            case HARD -> 2;
        };
    }
}
