package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.CompleteSessionRequest;
import com.mockinterview.backend.dto.InterviewReportSummary;
import com.mockinterview.backend.dto.ReportResponse;
import com.mockinterview.backend.dto.TopicBreakdown;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.repository.EvaluationRepository;
import com.mockinterview.backend.repository.ReportRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock private InterviewSessionService sessionService;
    @Mock private EvaluationRepository evaluationRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private PerRequestChatClientFactory chatClientFactory;

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    @InjectMocks
    private ReportService reportService;

    private User user;

    private InterviewSession completedSession(int questionCount) {
        InterviewSession session = new InterviewSession();
        session.setId(1L);
        session.setUser(user);
        session.setTopic(Topic.DSA);
        session.setStatus(SessionStatus.COMPLETED);
        session.setTargetQuestionCount(questionCount);
        session.setQuestionsAsked(questionCount);
        return session;
    }

    private Evaluation evaluationWithScore(InterviewSession session, int sequenceNumber, int score) {
        return evaluationWithScore(session, session.getTopic(), sequenceNumber, score);
    }

    private Evaluation evaluationWithScore(InterviewSession session, Topic topic, int sequenceNumber, int score) {
        Question question = new Question();
        question.setSession(session);
        question.setSequenceNumber(sequenceNumber);
        question.setTopic(topic);
        question.setDifficulty(Difficulty.EASY);
        question.setPromptText("Q" + sequenceNumber);

        Answer answer = new Answer();
        answer.setQuestion(question);
        answer.setAnswerText("A" + sequenceNumber);

        Evaluation evaluation = new Evaluation();
        evaluation.setAnswer(answer);
        evaluation.setScore(score);
        evaluation.setCorrectness(Correctness.CORRECT);
        evaluation.setFeedback("feedback " + sequenceNumber);
        evaluation.setStrengths(List.of());
        evaluation.setWeaknesses(List.of());
        return evaluation;
    }

    /** Stubs the ChatClient fluent chain used by ReportService's narrative-summary call. */
    private void stubNarrativeToReturn(InterviewReportSummary summary) {
        when(chatClientFactory.forRequest(anyString(), any(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(InterviewReportSummary.class)).thenReturn(summary);
    }

    private ReportResponse complete(Long sessionId) {
        return reportService.completeSession(user, sessionId, null, "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);
    }

    @Test
    void completeSessionRejectsASessionThatIsNotYetCompleted() {
        user = new User();
        user.setId(1L);
        InterviewSession inProgress = new InterviewSession();
        inProgress.setStatus(SessionStatus.IN_PROGRESS);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(inProgress);

        assertThatThrownBy(() -> complete(1L))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(reportRepository, chatClientFactory);
    }

    @Test
    void completeSessionAveragesScoresAndFlagsAStrongTopicWhenScoreIsHigh() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(2);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session))
                .thenReturn(List.of(evaluationWithScore(session, 1, 80), evaluationWithScore(session, 2, 90)));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubNarrativeToReturn(new InterviewReportSummary(List.of(), List.of(), "Strong showing overall."));

        ReportResponse response = complete(1L);

        assertThat(response.overallScore()).isEqualTo(85); // (80+90)/2
        assertThat(response.strongTopics()).containsExactly("DSA");
        assertThat(response.weakTopics()).isEmpty();
        assertThat(response.summaryText()).isEqualTo("Strong showing overall.");
        assertThat(response.breakdown()).hasSize(2);
        verify(reportRepository, times(1)).save(any());
    }

    @Test
    void completeSessionFlagsAWeakTopicWhenScoreIsLow() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session))
                .thenReturn(List.of(evaluationWithScore(session, 1, 20)));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubNarrativeToReturn(new InterviewReportSummary(List.of(), List.of(), "Needs more practice."));

        ReportResponse response = complete(1L);

        assertThat(response.overallScore()).isEqualTo(20);
        assertThat(response.weakTopics()).containsExactly("DSA");
        assertThat(response.strongTopics()).isEmpty();
    }

    @Test
    void completeSessionGroupsStrongAndWeakTopicsPerTopicAcrossAMultiTopicSession() {
        user = new User();
        user.setId(1L);
        // session.topic reflects wherever the loop ended up (SYSTEM_DESIGN, the last topic run) —
        // the report must still credit/flag DSA and SYSTEM_DESIGN separately, not just that one.
        InterviewSession session = completedSession(3);
        session.setTopic(Topic.SYSTEM_DESIGN);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of(
                evaluationWithScore(session, Topic.DSA, 1, 90),
                evaluationWithScore(session, Topic.DSA, 2, 80),
                evaluationWithScore(session, Topic.SYSTEM_DESIGN, 3, 20)));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubNarrativeToReturn(new InterviewReportSummary(List.of(), List.of(), "Mixed performance across topics."));

        ReportResponse response = complete(1L);

        assertThat(response.strongTopics()).containsExactly("DSA");
        assertThat(response.weakTopics()).containsExactly("SYSTEM_DESIGN");
        assertThat(response.topicBreakdown())
                .extracting(TopicBreakdown::topic, TopicBreakdown::averageScore, TopicBreakdown::questionCount)
                .containsExactly(
                        tuple(Topic.DSA, 85, 2),
                        tuple(Topic.SYSTEM_DESIGN, 20, 1));
    }

    @Test
    void completeSessionPrefersTheLlmsSubtopicReadOverTheHeuristicWhenItIsNonEmpty() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session))
                .thenReturn(List.of(evaluationWithScore(session, 1, 80)));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubNarrativeToReturn(new InterviewReportSummary(
                List.of("Recursion basics"), List.of("Dynamic programming"), "Solid fundamentals."));

        ReportResponse response = complete(1L);

        assertThat(response.strongTopics()).containsExactly("Recursion basics");
        assertThat(response.weakTopics()).containsExactly("Dynamic programming");
    }

    @Test
    void completeSessionIsIdempotentAndDoesNotRegenerateAnExistingReport() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        Report existing = new Report();
        existing.setSession(session);
        existing.setOverallScore(75);
        existing.setQuestionCount(1);

        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.of(existing));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session))
                .thenReturn(List.of(evaluationWithScore(session, 1, 75)));

        ReportResponse response = complete(1L);

        assertThat(response.overallScore()).isEqualTo(75);
        verify(reportRepository, never()).save(any());
        verifyNoInteractions(chatClientFactory);
    }

    @Test
    void completeSessionPersistsAndReturnsTheTabSwitchCount() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session))
                .thenReturn(List.of(evaluationWithScore(session, 1, 80)));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubNarrativeToReturn(new InterviewReportSummary(List.of(), List.of(), "Fine."));

        ReportResponse response = reportService.completeSession(
                user, 1L, new CompleteSessionRequest(3), "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.tabSwitchCount()).isEqualTo(3);
        assertThat(session.getTabSwitchCount()).isEqualTo(3);
    }

    @Test
    void completeSessionSkipsNarrativeGenerationWhenTheSessionHasNoLlmKey() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        session.setLlmAvailable(false);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session))
                .thenReturn(List.of(evaluationWithScore(session, 1, 80)));
        when(reportRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ReportResponse response = complete(1L);

        assertThat(response.overallScore()).isEqualTo(80);
        assertThat(response.strongTopics()).containsExactly("DSA"); // heuristic fallback still applies
        assertThat(response.summaryText()).isNull();
        verifyNoInteractions(chatClientFactory);
    }

    @Test
    void getReportThrowsWhenNoReportHasBeenGeneratedYet() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getReport(user, 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shareReportGeneratesATokenOnFirstCallAndPersistsIt() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        Report report = new Report();
        report.setSession(session);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.of(report));

        String token = reportService.shareReport(user, 1L);

        assertThat(token).isNotBlank();
        assertThat(report.getShareToken()).isEqualTo(token);
        verify(reportRepository, times(1)).save(report);
    }

    @Test
    void shareReportIsIdempotentAndReusesTheExistingToken() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        Report report = new Report();
        report.setSession(session);
        report.setShareToken("already-shared-token");
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.of(report));

        String token = reportService.shareReport(user, 1L);

        assertThat(token).isEqualTo("already-shared-token");
        verify(reportRepository, never()).save(any());
    }

    @Test
    void shareReportThrowsWhenNoReportHasBeenGeneratedYet() {
        user = new User();
        user.setId(1L);
        InterviewSession session = completedSession(1);
        when(sessionService.getOwnedSession(user, 1L)).thenReturn(session);
        when(reportRepository.findBySession(session)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.shareReport(user, 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shareReportPropagatesTheOwnershipCheckFromGetOwnedSession() {
        user = new User();
        user.setId(1L);
        when(sessionService.getOwnedSession(user, 1L))
                .thenThrow(new IllegalArgumentException("Session not found"));

        assertThatThrownBy(() -> reportService.shareReport(user, 1L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(reportRepository);
    }

    @Test
    void getPublicReportReturnsReportDataForAKnownToken() {
        InterviewSession session = completedSession(1);
        Report report = new Report();
        report.setSession(session);
        report.setOverallScore(88);
        report.setQuestionCount(1);
        report.setShareToken("public-token");
        when(reportRepository.findByShareToken("public-token")).thenReturn(Optional.of(report));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session))
                .thenReturn(List.of(evaluationWithScore(session, 1, 88)));

        ReportResponse response = reportService.getPublicReport("public-token");

        assertThat(response.overallScore()).isEqualTo(88);
        assertThat(response.sessionId()).isEqualTo(session.getId());
    }

    @Test
    void getPublicReportThrowsNotFoundForAnUnknownToken() {
        when(reportRepository.findByShareToken("missing-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reportService.getPublicReport("missing-token"))
                .isInstanceOf(NoSuchElementException.class);
    }
}
