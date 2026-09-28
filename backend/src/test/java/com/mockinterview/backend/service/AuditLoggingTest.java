package com.mockinterview.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mockinterview.backend.dto.*;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.repository.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

/**
 * PLAN.md §8/§10: the server must never write the caller's raw LLM API key to any log line, even
 * though it needs the key in-memory to build a per-request ChatClient. This is the single most
 * safety-critical regression test in the project, given the BYO-key privacy claims in §8 — an
 * audit/logging statement added later (naively logging the request or an exception) is the kind
 * of regression that's otherwise easy to introduce silently.
 */
@ExtendWith(MockitoExtension.class)
class AuditLoggingTest {

    private static final String SECRET_API_KEY = "sk-do-not-log-this-3f9a7c21";

    @Mock private InterviewSessionRepository sessionRepository;
    @Mock private QuestionRepository questionRepository;
    @Mock private AnswerRepository answerRepository;
    @Mock private EvaluationRepository evaluationRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private StaticQuestionBankService questionBank;
    @Mock private QuestionSelectionService questionSelectionService;
    @Mock private AdaptiveDifficultyService adaptiveDifficultyService;
    @Mock private PerRequestChatClientFactory chatClientFactory;
    @Mock private CodeExecutionService codeExecutionService;

    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;
    // Phase 4 collaborators: pack-only paths, never reached by these handbook-topic tests.
    @Mock private PackQuestionSource packQuestionSource;
    @Mock private PackQuizService packQuizService;
    @Mock private ServerChatClientProvider serverChatClientProvider;
    @Mock private MeteredLlmCall meteredLlmCall;
    @Mock private StudyPackRepository studyPackRepository;

    private InterviewSessionService service;

    /** Real handbook source + LLM resolver around the mocks above, so the tests keep stubbing
     *  questionBank / questionSelectionService / chatClientFactory exactly as before. */
    private InterviewSessionService newService() {
        return new InterviewSessionService(sessionRepository, questionRepository, answerRepository,
                evaluationRepository, reportRepository,
                new HandbookQuestionSource(questionBank, questionSelectionService), packQuestionSource,
                adaptiveDifficultyService,
                new SessionLlmResolver(chatClientFactory, serverChatClientProvider, meteredLlmCall,
                        new com.mockinterview.backend.config.QuizProperties(null, null, null, null, null, null, null, null, null, null, null)),
                codeExecutionService, packQuizService, new PackTitleLookup(studyPackRepository));
    }

    private ListAppender<ILoggingEvent> appender;
    private Logger rootLogger;

    @BeforeEach
    void attachLogAppender() {
        service = newService();
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        rootLogger.detachAppender(appender);
    }

    @Test
    void submitAnswerNeverLogsTheRawApiKey() {
        User user = new User();
        user.setId(1L);

        InterviewSession session = new InterviewSession();
        session.setId(1L);
        session.setUser(user);
        session.setTopic(Topic.DSA);
        session.setCurrentDifficulty(Difficulty.EASY);
        session.setTargetQuestionCount(2);
        session.setQuestionsAsked(0);
        session.setStatus(SessionStatus.IN_PROGRESS);

        Question question = new Question();
        question.setId(1L);
        question.setSession(session);
        question.setSequenceNumber(1);
        question.setTopic(Topic.DSA);
        question.setDifficulty(Difficulty.EASY);
        question.setPromptText("Explain Big-O.");
        question.setQuestionType(QuestionType.CONCEPTUAL);
        question.setSourceChunkId("dsa-e1");

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(question));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        when(adaptiveDifficultyService.computeNext(any(), anyInt(), any(), anyBoolean())).thenReturn(Difficulty.EASY);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(question));
        when(questionSelectionService.pickNext(any(), any(), anySet())).thenReturn(Optional.empty());
        when(questionBank.pickNext(any(), any(), any(), anySet()))
                .thenReturn(new StaticQuestionEntry("dsa-e2", Difficulty.EASY, QuestionType.CONCEPTUAL, "Another question",
                        null, null, null, null, null));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(chatClientFactory.forRequest(eq(SECRET_API_KEY), any(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(EvaluationResult.class)).thenReturn(new EvaluationResult(
                90, Correctness.CORRECT, "Great answer.", List.of(), List.of(), DifficultyDelta.SAME));

        service.submitAnswer(user, 1L, new SubmitAnswerRequest("O(n log n).", null, null, null),
                SECRET_API_KEY, PerRequestChatClientFactory.Provider.GROQ, null);

        List<String> loggedMessages = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(loggedMessages).isNotEmpty(); // sanity check the audit log line actually fired
        assertThat(loggedMessages).noneMatch(message -> message.contains(SECRET_API_KEY));
    }
}
