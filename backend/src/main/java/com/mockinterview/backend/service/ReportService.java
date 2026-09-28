package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.CompleteSessionRequest;
import com.mockinterview.backend.dto.InterviewReportSummary;
import com.mockinterview.backend.dto.PackRef;
import com.mockinterview.backend.dto.QuestionBreakdown;
import com.mockinterview.backend.dto.ReportResponse;
import com.mockinterview.backend.dto.TopicBreakdown;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.repository.EvaluationRepository;
import com.mockinterview.backend.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 1: a simple average + heuristic strong/weak topic split.
 * Phase 4: on first generation, also asks the LLM for a narrative summary and a subtopic-level
 * read of strengths/weaknesses (Report.summaryText, refined strong/weak topics).
 * Multi-topic loop sessions: strongTopics/weakTopics and the report's per-topic breakdown are both
 * computed by grouping evaluations by question.getTopic() (each Question keeps its own topic even
 * after session.topic has moved on) — a single-topic session is just the one-group case of this.
 * Study-pack quizzes (topic STUDY_PACK): the narrative runs on the server key, charged to the
 * user's quota (SessionLlmResolver); strong/weak areas are the document sections the questions came
 * from rather than the one hidden topic; the response carries the pack's title.
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** report_strong_topics / report_weak_topics are VARCHAR(255). */
    private static final int MAX_AREA_CHARS = 255;

    private final InterviewSessionService sessionService;
    private final EvaluationRepository evaluationRepository;
    private final ReportRepository reportRepository;
    private final SessionLlmResolver sessionLlmResolver;
    private final PackTitleLookup packTitleLookup;

    @Transactional
    public ReportResponse completeSession(User user, Long sessionId, CompleteSessionRequest request, String apiKey,
                                           PerRequestChatClientFactory.Provider provider, String model) {
        InterviewSession session = sessionService.getOwnedSession(user, sessionId);
        if (session.getStatus() != SessionStatus.COMPLETED) {
            throw new IllegalStateException("Session is not yet completed — answer all questions first");
        }

        PackRef pack = packTitleLookup.ref(session);
        return reportRepository.findBySession(session)
                .map(existing -> toResponse(session, existing, loadEvaluations(session), pack))
                .orElseGet(() -> generateAndPersist(user, session, pack, request, apiKey, provider, model));
    }

    @Transactional(readOnly = true)
    public ReportResponse getReport(User user, Long sessionId) {
        InterviewSession session = sessionService.getOwnedSession(user, sessionId);
        Report report = reportRepository.findBySession(session)
                .orElseThrow(() -> new IllegalStateException("Report not generated yet — call POST /sessions/{id}/complete first"));
        return toResponse(session, report, loadEvaluations(session), packTitleLookup.ref(session));
    }

    /** Lazily mints a share token the first time it's requested; idempotent on repeat calls. */
    @Transactional
    public String shareReport(User user, Long sessionId) {
        InterviewSession session = sessionService.getOwnedSession(user, sessionId);
        Report report = reportRepository.findBySession(session)
                .orElseThrow(() -> new IllegalStateException("Report not generated yet — call POST /sessions/{id}/complete first"));

        if (report.getShareToken() == null) {
            report.setShareToken(UUID.randomUUID().toString());
            reportRepository.save(report);
        }
        return report.getShareToken();
    }

    /** Resolves a report by its public share token only — never by session id — so an unknown
     *  token can't be used to probe for the existence of a given session. A pack quiz's shared
     *  report shows the pack's title, nothing else about the pack (not even its id). */
    @Transactional(readOnly = true)
    public ReportResponse getPublicReport(String shareToken) {
        Report report = reportRepository.findByShareToken(shareToken)
                .orElseThrow(() -> new NoSuchElementException("Report not found"));
        InterviewSession session = report.getSession();
        return toResponse(session, report, loadEvaluations(session), packTitleLookup.ref(session).titleOnly());
    }

    private ReportResponse generateAndPersist(User user, InterviewSession session, PackRef pack,
                                               CompleteSessionRequest request, String apiKey,
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

        List<String> strongTopics = new ArrayList<>();
        List<String> weakTopics = new ArrayList<>();
        for (Map.Entry<String, List<Evaluation>> entry : groupByArea(evaluations, pack).entrySet()) {
            double topicAverage = entry.getValue().stream().mapToInt(Evaluation::getScore).average().orElse(0);
            if (topicAverage >= 70) {
                strongTopics.add(entry.getKey());
            } else if (topicAverage <= 40) {
                weakTopics.add(entry.getKey());
            }
        }
        report.setStrongTopics(strongTopics);
        report.setWeakTopics(weakTopics);

        if (session.isLlmAvailable()) {
            SessionLlm llm = sessionLlmResolver.forSession(session, user, apiKey, provider, model);
            log.info("Generating narrative report: sessionId={}, {}", session.getId(), llm.describe());
            InterviewReportSummary summary = generateNarrative(llm, evaluations, overallScore, pack);
            report.setSummaryText(summary.narrativeSummary());
            if (summary.strongTopics() != null && !summary.strongTopics().isEmpty()) {
                report.setStrongTopics(capped(summary.strongTopics()));
            }
            if (summary.weakTopics() != null && !summary.weakTopics().isEmpty()) {
                report.setWeakTopics(capped(summary.weakTopics()));
            }
        } else {
            log.info("Skipping narrative report generation — session has no LLM key: sessionId={}", session.getId());
        }

        reportRepository.save(report);
        return toResponse(session, report, evaluations, pack);
    }

    private InterviewReportSummary generateNarrative(SessionLlm llm, List<Evaluation> evaluations, int overallScore,
                                                       PackRef pack) {
        String breakdown = evaluations.stream()
                .map(e -> "Q%d%s (%s difficulty, score %d/100, %s): %s | Strengths: %s | Weaknesses: %s".formatted(
                        e.getAnswer().getQuestion().getSequenceNumber(),
                        sectionNote(e.getAnswer().getQuestion()),
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

        String topicsCovered = evaluations.stream()
                .map(e -> topicLabel(e.getAnswer().getQuestion(), pack))
                .distinct()
                .collect(Collectors.joining(", "));

        String user = """
                Topics covered: %s
                Overall score: %d/100 across %d questions.

                Per-question breakdown:
                %s

                Based on the breakdown above (not just the overall topic name(s)), identify the specific
                subtopics the candidate was strongest and weakest in, and write a concise, encouraging
                but honest 3-5 sentence narrative summary of their performance.
                """.formatted(topicsCovered, overallScore, evaluations.size(), breakdown);

        return llm.entity(system, user, InterviewReportSummary.class);
    }

    private List<Evaluation> loadEvaluations(InterviewSession session) {
        return evaluationRepository.findBySessionOrderByQuestionSequence(session);
    }

    private ReportResponse toResponse(InterviewSession session, Report report, List<Evaluation> evaluations,
                                      PackRef pack) {
        List<QuestionBreakdown> breakdown = evaluations.stream()
                .map(e -> new QuestionBreakdown(
                        e.getAnswer().getQuestion().getSequenceNumber(),
                        e.getAnswer().getQuestion().getTopic(),
                        e.getAnswer().getQuestion().getPromptText(),
                        e.getAnswer().getQuestion().getDifficulty(),
                        e.getAnswer().getAnswerText(),
                        e.getScore(),
                        e.getCorrectness(),
                        e.getFeedback()
                ))
                .toList();

        List<TopicBreakdown> topicBreakdown = groupByTopic(evaluations).entrySet().stream()
                .map(entry -> new TopicBreakdown(
                        entry.getKey(),
                        (int) Math.round(entry.getValue().stream().mapToInt(Evaluation::getScore).average().orElse(0)),
                        entry.getValue().size()
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
                session.getTabSwitchCount(),
                topicBreakdown,
                pack.packId(),
                pack.packTitle()
        );
    }

    /** LinkedHashMap keeps topics in first-appearance (i.e. interview) order — evaluations are
     *  already loaded ordered by question sequence, so this preserves that instead of an arbitrary
     *  hash order. A single-topic session naturally groups into just the one entry. */
    private Map<Topic, List<Evaluation>> groupByTopic(List<Evaluation> evaluations) {
        return evaluations.stream().collect(Collectors.groupingBy(
                e -> e.getAnswer().getQuestion().getTopic(), LinkedHashMap::new, Collectors.toList()));
    }

    /** Strong/weak grouping key: the topic for a handbook question; for a pack question the
     *  document section it came from (a pack quiz has only the one hidden topic), falling back to
     *  the pack's title. For handbook sessions this is exactly the old grouping by topic. */
    private Map<String, List<Evaluation>> groupByArea(List<Evaluation> evaluations, PackRef pack) {
        return evaluations.stream().collect(Collectors.groupingBy(
                e -> areaLabel(e.getAnswer().getQuestion(), pack), LinkedHashMap::new, Collectors.toList()));
    }

    private static String areaLabel(Question question, PackRef pack) {
        if (question.getTopic() == Topic.STUDY_PACK && question.getSourceSection() != null
                && !question.getSourceSection().isBlank()) {
            return truncate(question.getSourceSection().strip());
        }
        return truncate(topicLabel(question, pack));
    }

    /** The pack's title stands in for the hidden STUDY_PACK topic (in the narrative prompt too). */
    private static String topicLabel(Question question, PackRef pack) {
        if (question.getTopic() == Topic.STUDY_PACK) {
            return pack.packTitle() != null ? pack.packTitle() : "Study pack";
        }
        return question.getTopic().name();
    }

    /** Lets the narrative name document sections for a pack quiz; empty for handbook questions,
     *  so their prompt is unchanged. */
    private static String sectionNote(Question question) {
        return question.getSourceSection() != null && !question.getSourceSection().isBlank()
                ? " [section: " + question.getSourceSection().strip() + "]" : "";
    }

    private static List<String> capped(List<String> areas) {
        return areas.stream().filter(a -> a != null && !a.isBlank()).map(ReportService::truncate)
                .collect(Collectors.toCollection(ArrayList::new)); // mutable: becomes an entity collection
    }

    private static String truncate(String s) {
        return s.length() <= MAX_AREA_CHARS ? s : s.substring(0, MAX_AREA_CHARS);
    }

    private int difficultyOrdinal(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> 0;
            case MEDIUM -> 1;
            case HARD -> 2;
        };
    }
}
