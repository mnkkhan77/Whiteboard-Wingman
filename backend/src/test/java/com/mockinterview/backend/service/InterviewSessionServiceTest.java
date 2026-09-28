package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.*;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Exercises the core "submit answer -> get next question" loop (PLAN.md §1) without ever
 * calling a real LLM: the ChatClient fluent chain is mocked to return a canned EvaluationResult.
 */
@ExtendWith(MockitoExtension.class)
class InterviewSessionServiceTest {

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

    private User user;

    @BeforeEach
    void setUp() {
        service = newService();
        user = new User();
        user.setId(1L);
        user.setEmail("candidate@example.com");
    }

    private void stubChatClientToReturn(EvaluationResult result) {
        when(chatClientFactory.forRequest(anyString(), any(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(EvaluationResult.class)).thenReturn(result);
    }

    /** No MCQ/CODING bank content by default, so a session's only section is the verbal one. */
    private void stubNoMcqOrCodingContent() {
        lenient().when(questionBank.countByType(any(), eq(QuestionType.MCQ))).thenReturn(0);
        lenient().when(questionBank.countByType(any(), eq(QuestionType.CODING))).thenReturn(0);
    }

    private StaticQuestionEntry conceptualEntry(String id, Difficulty difficulty, String promptText) {
        return new StaticQuestionEntry(id, difficulty, QuestionType.CONCEPTUAL, promptText, null, null, null, null, null);
    }

    private StaticQuestionEntry codingEntry(String id, Difficulty difficulty, String promptText) {
        return new StaticQuestionEntry(id, difficulty, QuestionType.CODING, promptText, null, null, null, null, null);
    }

    private InterviewSession newSession(int questionsAsked, int targetQuestionCount, SessionStatus status) {
        InterviewSession session = new InterviewSession();
        session.setId(1L);
        session.setUser(user);
        session.setTopic(Topic.DSA);
        session.setStartingDifficulty(Difficulty.EASY);
        session.setCurrentDifficulty(Difficulty.EASY);
        session.setTargetQuestionCount(targetQuestionCount);
        session.setQuestionsAsked(questionsAsked);
        session.setStatus(status);
        return session;
    }

    private Question newQuestion(InterviewSession session, int sequenceNumber) {
        return newQuestion(session, sequenceNumber, QuestionType.CONCEPTUAL);
    }

    private Question newQuestion(InterviewSession session, int sequenceNumber, QuestionType type) {
        Question question = new Question();
        question.setId((long) sequenceNumber);
        question.setSession(session);
        question.setSequenceNumber(sequenceNumber);
        question.setTopic(Topic.DSA);
        question.setDifficulty(Difficulty.EASY);
        question.setPromptText("Some question " + sequenceNumber);
        question.setQuestionType(type);
        question.setSourceChunkId("dsa-e" + sequenceNumber);
        return question;
    }

    @Test
    void startSessionCreatesASessionAndServesTheFirstQuestion() {
        stubNoMcqOrCodingContent();
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            InterviewSession s = inv.getArgument(0);
            s.setId(1L); // simulates the id JPA would assign on insert
            return s;
        });
        when(chatClientFactory.forRequest(anyString(), any(), any())).thenReturn(chatClient);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(any())).thenReturn(List.of());
        when(questionSelectionService.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), anySet())).thenReturn(Optional.empty());
        when(questionBank.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), eq(QuestionType.CONCEPTUAL), anySet()))
                .thenReturn(codingEntry("dsa-e1", Difficulty.EASY, "Two Sum"));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StartSessionRequest request = new StartSessionRequest(Topic.DSA, Difficulty.EASY, 3);
        SessionStartResponse response = service.startSession(
                user, request, "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.sessionId()).isEqualTo(1L);
        assertThat(response.firstQuestion().promptText()).isEqualTo("Two Sum");
        assertThat(response.firstQuestion().sequenceNumber()).isEqualTo(1);
    }

    @Test
    void startSessionPrefersTheRagQuestionOverTheStaticBankWhenAVectorStoreMatchExists() {
        stubNoMcqOrCodingContent();
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            InterviewSession s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(chatClientFactory.forRequest(anyString(), any(), any())).thenReturn(chatClient);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(any())).thenReturn(List.of());
        when(questionSelectionService.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), anySet()))
                .thenReturn(Optional.of(conceptualEntry(
                        "DSA:interview-content/dsa/x.html:0", Difficulty.EASY,
                        "How does a HashMap resolve collisions internally?")));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StartSessionRequest request = new StartSessionRequest(Topic.DSA, Difficulty.EASY, 3);
        SessionStartResponse response = service.startSession(
                user, request, "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.firstQuestion().promptText()).isEqualTo("How does a HashMap resolve collisions internally?");
        verify(questionBank, never()).pickNext(any(), any(), any(), anySet());
    }

    @Test
    void submitAnswerMidSessionPersistsEvaluationAndServesTheNextQuestion() {
        stubNoMcqOrCodingContent();
        InterviewSession session = newSession(0, 2, SessionStatus.IN_PROGRESS);
        Question currentQuestion = newQuestion(session, 1);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of()); // no prior answers yet
        when(adaptiveDifficultyService.computeNext(Difficulty.EASY, 80, DifficultyDelta.SAME, false)).thenReturn(Difficulty.EASY);
        // First call (used-ids lookup before creating the next question) sees only the current question.
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(currentQuestion));
        when(questionSelectionService.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), anySet())).thenReturn(Optional.empty());
        when(questionBank.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), eq(QuestionType.CONCEPTUAL), anySet()))
                .thenReturn(codingEntry("dsa-e2", Difficulty.EASY, "Reverse a linked list"));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EvaluationResult canned = new EvaluationResult(
                80, Correctness.CORRECT, "Solid answer.",
                List.of("Clear approach"), List.of("Could discuss edge cases"), DifficultyDelta.SAME);
        stubChatClientToReturn(canned);

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("Use a hash map.", null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.evaluation().score()).isEqualTo(80);
        assertThat(response.sessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(response.nextQuestion()).isNotNull();
        assertThat(response.nextQuestion().promptText()).isEqualTo("Reverse a linked list");
        assertThat(response.progress().current()).isEqualTo(1);
        assertThat(response.progress().total()).isEqualTo(2);
        assertThat(response.sectionComplete()).isFalse();

        verify(evaluationRepository).save(argThat(e -> e.getScore() == 80 && e.getCorrectness() == Correctness.CORRECT));
        verify(adaptiveDifficultyService).computeNext(Difficulty.EASY, 80, DifficultyDelta.SAME, false);
        assertThat(session.getCurrentDifficulty()).isEqualTo(Difficulty.EASY);
    }

    @Test
    void submitAnswerUsesTheAdaptedDifficultyWhenPickingTheNextQuestion() {
        stubNoMcqOrCodingContent();
        InterviewSession session = newSession(0, 2, SessionStatus.IN_PROGRESS);
        Question currentQuestion = newQuestion(session, 1);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        // A near-perfect score pushes difficulty up to MEDIUM regardless of the model's own SAME suggestion.
        when(adaptiveDifficultyService.computeNext(Difficulty.EASY, 95, DifficultyDelta.SAME, false)).thenReturn(Difficulty.MEDIUM);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(currentQuestion));
        when(questionSelectionService.pickNext(eq(Topic.DSA), eq(Difficulty.MEDIUM), anySet())).thenReturn(Optional.empty());
        when(questionBank.pickNext(eq(Topic.DSA), eq(Difficulty.MEDIUM), eq(QuestionType.CONCEPTUAL), anySet()))
                .thenReturn(conceptualEntry("dsa-m1", Difficulty.MEDIUM, "A medium question"));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubChatClientToReturn(new EvaluationResult(
                95, Correctness.CORRECT, "Excellent.", List.of("Thorough"), List.of(), DifficultyDelta.SAME));

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("A great answer.", null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(session.getCurrentDifficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(response.nextQuestion().difficulty()).isEqualTo(Difficulty.MEDIUM);
        verify(questionSelectionService).pickNext(eq(Topic.DSA), eq(Difficulty.MEDIUM), anySet());
    }

    @Test
    void submitAnswerOnTheLastQuestionCompletesTheSessionWithNoNextQuestion() {
        stubNoMcqOrCodingContent();
        InterviewSession session = newSession(1, 2, SessionStatus.IN_PROGRESS); // 1 asked, target 2 -> this is the last one
        Question currentQuestion = newQuestion(session, 2);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        when(adaptiveDifficultyService.computeNext(any(), anyInt(), any(), anyBoolean())).thenReturn(Difficulty.EASY);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(newQuestion(session, 1), currentQuestion));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        EvaluationResult canned = new EvaluationResult(
                50, Correctness.PARTIALLY_CORRECT, "Partially correct.",
                List.of(), List.of("Missed the edge case"), DifficultyDelta.SAME);
        stubChatClientToReturn(canned);

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("An answer.", null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.sessionStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(response.nextQuestion()).isNull();
        assertThat(response.sectionComplete()).isFalse(); // no next section exists, so this is a full completion, not a section break
        assertThat(response.progress().current()).isEqualTo(2);

        // No question should be picked once the session is complete.
        verify(questionBank, never()).pickNext(any(), any(), any(), anySet());
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(session.getCompletedAt()).isNotNull();
    }

    @Test
    void startSessionWithMultipleTopicsQueuesTheRestAndCountsAllOfThemInTheGrandTotal() {
        stubNoMcqOrCodingContent();
        when(sessionRepository.save(any())).thenAnswer(inv -> {
            InterviewSession s = inv.getArgument(0);
            s.setId(1L);
            return s;
        });
        when(chatClientFactory.forRequest(anyString(), any(), any())).thenReturn(chatClient);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(any())).thenReturn(List.of());
        when(questionSelectionService.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), anySet())).thenReturn(Optional.empty());
        when(questionBank.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), eq(QuestionType.CONCEPTUAL), anySet()))
                .thenReturn(conceptualEntry("dsa-e1", Difficulty.EASY, "Two Sum"));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        StartSessionRequest request = new StartSessionRequest(
                Topic.DSA, Difficulty.EASY, 2, List.of(Topic.DSA, Topic.SYSTEM_DESIGN));
        SessionStartResponse response = service.startSession(
                user, request, "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.firstQuestion().topic()).isEqualTo(Topic.DSA);
        verify(sessionRepository).save(argThat(s ->
                s.getTopic() == Topic.DSA && s.getTopicQueue().equals(List.of(Topic.SYSTEM_DESIGN))));
    }

    @Test
    void multiTopicSessionAdvancesToTheNextTopicResettingDifficultyOnceTheCurrentTopicsSectionsAreExhausted() {
        stubNoMcqOrCodingContent(); // neither topic has MCQ/CODING content -> verbal-only for both
        InterviewSession session = newSession(0, 1, SessionStatus.IN_PROGRESS); // target 1: one verbal question finishes DSA
        session.setTopicQueue(new ArrayList<>(List.of(Topic.SYSTEM_DESIGN)));
        Question currentQuestion = newQuestion(session, 1); // DSA, CONCEPTUAL, EASY

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        // Simulates difficulty having drifted up during the DSA topic — the topic switch must reset it.
        when(adaptiveDifficultyService.computeNext(Difficulty.EASY, 80, DifficultyDelta.SAME, false)).thenReturn(Difficulty.HARD);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(currentQuestion));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubChatClientToReturn(new EvaluationResult(
                80, Correctness.CORRECT, "Good.", List.of(), List.of(), DifficultyDelta.SAME));

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("An answer.", null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.sessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS); // not done — SYSTEM_DESIGN is still queued
        assertThat(response.sectionComplete()).isTrue();
        assertThat(response.nextSectionType()).isEqualTo(QuestionType.CONCEPTUAL);
        assertThat(response.nextTopic()).isEqualTo(Topic.SYSTEM_DESIGN);
        assertThat(response.nextQuestion()).isNull(); // waiting for startNextSection, same as an in-topic section break
        assertThat(response.progress().total()).isEqualTo(2); // 1 (DSA) + 1 (SYSTEM_DESIGN), from the very first question onward

        assertThat(session.getTopic()).isEqualTo(Topic.SYSTEM_DESIGN);
        assertThat(session.getTopicQueue()).isEmpty();
        assertThat(session.getCurrentDifficulty()).isEqualTo(Difficulty.EASY); // reset, not the drifted HARD
    }

    @Test
    void sessionOnlyCompletesOnceTheLastQueuedTopicsLastSectionFinishes() {
        stubNoMcqOrCodingContent();
        InterviewSession session = newSession(1, 1, SessionStatus.IN_PROGRESS); // already advanced onto the final topic
        session.setTopic(Topic.SYSTEM_DESIGN);
        Question currentQuestion = newQuestion(session, 2);
        currentQuestion.setTopic(Topic.SYSTEM_DESIGN);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        when(adaptiveDifficultyService.computeNext(any(), anyInt(), any(), anyBoolean())).thenReturn(Difficulty.EASY);
        Question priorDsaQuestion = newQuestion(session, 1);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(priorDsaQuestion, currentQuestion));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        stubChatClientToReturn(new EvaluationResult(
                70, Correctness.CORRECT, "Fine.", List.of(), List.of(), DifficultyDelta.SAME));

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("An answer.", null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.sessionStatus()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(response.nextTopic()).isNull();
        assertThat(response.sectionComplete()).isFalse();
        assertThat(session.getStatus()).isEqualTo(SessionStatus.COMPLETED);
        verify(questionBank, never()).pickNext(any(), any(), any(), anySet());
    }

    @Test
    void startSessionRejectsKeylessStartWhenTheTopicHasNoMcqOrCodingContent() {
        stubNoMcqOrCodingContent();
        StartSessionRequest request = new StartSessionRequest(Topic.DSA, Difficulty.EASY, 3);

        assertThatThrownBy(() -> service.startSession(user, request, "", PerRequestChatClientFactory.Provider.GROQ, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(chatClientFactory, sessionRepository, questionRepository);
    }

    @Test
    void submitAnswerGradesACodingAnswerFromTestResultsWhenSessionHasNoLlmKey() {
        InterviewSession session = newSession(0, 2, SessionStatus.IN_PROGRESS);
        session.setLlmAvailable(false);
        Question currentQuestion = newQuestion(session, 1, QuestionType.CODING);
        Question.TestCase testCase = new Question.TestCase();
        testCase.setInput("1 2");
        testCase.setExpectedOutput("3");
        currentQuestion.setTestCases(List.of(testCase, testCase));

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        when(adaptiveDifficultyService.computeNext(any(), anyInt(), any(), anyBoolean())).thenReturn(Difficulty.EASY);
        when(questionBank.countByType(Topic.DSA, QuestionType.CODING)).thenReturn(1); // this is the only CODING question -> section completes here
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(currentQuestion));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CodeRunResponse.TestCaseResult passResult = new CodeRunResponse.TestCaseResult("1 2", "3", "3", true, null);
        CodeRunResponse.TestCaseResult failResult = new CodeRunResponse.TestCaseResult("1 2", "3", "4", false, null);
        when(codeExecutionService.run(eq("java"), anyString(), eq(currentQuestion.getTestCases())))
                .thenReturn(new CodeRunResponse(List.of(passResult, failResult), null));

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest(null, "public int add() { return 4; }", "java", null),
                "", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.evaluation().score()).isEqualTo(50);
        assertThat(response.evaluation().correctness()).isEqualTo(Correctness.PARTIALLY_CORRECT);
        assertThat(response.evaluation().feedback()).contains("Passed 1 of 2 test cases");
        verifyNoInteractions(chatClientFactory);
    }

    @Test
    void submitAnswerRejectsWhenSessionIsNotInProgress() {
        InterviewSession session = newSession(2, 2, SessionStatus.COMPLETED);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("An answer.", null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(answerRepository, evaluationRepository, chatClientFactory);
    }

    @Test
    void submitAnswerRejectsWhenTheSessionBelongsToAnotherUser() {
        InterviewSession session = newSession(0, 2, SessionStatus.IN_PROGRESS);
        User someoneElse = new User();
        someoneElse.setId(999L);
        session.setUser(someoneElse);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("An answer.", null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submitAnswerAcceptsACodeOnlySubmissionForALiveCodingQuestion() {
        // A live-coding-round question is answered primarily via code, not a written/spoken
        // explanation — a blank answerText alongside real code must not be rejected.
        InterviewSession session = newSession(0, 2, SessionStatus.IN_PROGRESS);
        Question currentQuestion = newQuestion(session, 1, QuestionType.CODING);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        when(adaptiveDifficultyService.computeNext(any(), anyInt(), any(), anyBoolean())).thenReturn(Difficulty.EASY);
        // 2 CODING questions available for the topic, so the coding "section" isn't complete after just 1.
        when(questionBank.countByType(Topic.DSA, QuestionType.CODING)).thenReturn(2);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(currentQuestion));
        when(questionSelectionService.pickNext(any(), any(), anySet())).thenReturn(Optional.empty());
        when(questionBank.pickNext(any(), any(), eq(QuestionType.CONCEPTUAL), anySet()))
                .thenReturn(codingEntry("dsa-e2", Difficulty.EASY, "Reverse a linked list"));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        stubChatClientToReturn(new EvaluationResult(
                75, Correctness.CORRECT, "Works.", List.of(), List.of(), DifficultyDelta.SAME));

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest("   ", "public int[] twoSum() { return null; }", "java", null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.evaluation().score()).isEqualTo(75);
        verify(answerRepository).save(argThat(a ->
                a.getAnswerText().isEmpty() && a.getCodeSubmission().contains("twoSum")));
    }

    @Test
    void submitAnswerRejectsWhenBothAnswerTextAndCodeAreBlank() {
        InterviewSession session = newSession(0, 2, SessionStatus.IN_PROGRESS);
        Question currentQuestion = newQuestion(session, 1);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));

        assertThatThrownBy(() -> service.submitAnswer(
                user, 1L, new SubmitAnswerRequest(null, "   ", null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(answerRepository, evaluationRepository, chatClientFactory);
    }

    @Test
    void submitAnswerRejectsAnMcqQuestionWithNoSelectedOption() {
        InterviewSession session = newSession(0, 2, SessionStatus.IN_PROGRESS);
        Question currentQuestion = newQuestion(session, 1, QuestionType.MCQ);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));

        assertThatThrownBy(() -> service.submitAnswer(
                user, 1L, new SubmitAnswerRequest(null, null, null, null),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(chatClientFactory);
    }

    @Test
    void submitAnswerScoresAnMcqQuestionDeterministicallyWithoutCallingTheLlm() {
        stubNoMcqOrCodingContent();
        InterviewSession session = newSession(0, 1, SessionStatus.IN_PROGRESS);
        Question currentQuestion = newQuestion(session, 1, QuestionType.MCQ);
        currentQuestion.setOptions(List.of("A", "B", "C"));
        currentQuestion.setCorrectOptionIndex(1);
        currentQuestion.setExplanation("B is correct because...");

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        when(adaptiveDifficultyService.computeNext(any(), anyInt(), any(), anyBoolean())).thenReturn(Difficulty.EASY);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(currentQuestion));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest(null, null, null, 1),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.evaluation().score()).isEqualTo(100);
        assertThat(response.evaluation().correctness()).isEqualTo(Correctness.CORRECT);
        assertThat(response.evaluation().feedback()).contains("Correct").contains("B is correct because");
        verifyNoInteractions(chatClientFactory);
        verify(answerRepository).save(argThat(a -> a.getAnswerText().contains("Selected option 2: B")));
    }

    @Test
    void submitAnswerReportsSectionCompleteWhenTheMcqSectionEndsWithACodingSectionRemaining() {
        InterviewSession session = newSession(0, 1, SessionStatus.IN_PROGRESS); // verbal target 1, already satisfied
        Question currentQuestion = newQuestion(session, 1, QuestionType.MCQ);
        currentQuestion.setOptions(List.of("A", "B"));
        currentQuestion.setCorrectOptionIndex(0);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)).thenReturn(Optional.of(currentQuestion));
        when(answerRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(evaluationRepository.findBySessionOrderByQuestionSequence(session)).thenReturn(List.of());
        when(adaptiveDifficultyService.computeNext(any(), anyInt(), any(), anyBoolean())).thenReturn(Difficulty.EASY);
        // Only 1 MCQ question total for this topic (so the MCQ section — 1 question — is now complete),
        // and 1 CODING question available (so a coding section follows next).
        when(questionBank.countByType(Topic.DSA, QuestionType.MCQ)).thenReturn(1);
        when(questionBank.countByType(Topic.DSA, QuestionType.CODING)).thenReturn(1);
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(currentQuestion));
        when(sessionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AnswerSubmitResponse response = service.submitAnswer(
                user, 1L, new SubmitAnswerRequest(null, null, null, 0),
                "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);

        assertThat(response.sectionComplete()).isTrue();
        assertThat(response.nextSectionType()).isEqualTo(QuestionType.CODING);
        assertThat(response.nextQuestion()).isNull();
        assertThat(response.sessionStatus()).isEqualTo(SessionStatus.IN_PROGRESS);
        verify(questionRepository, never()).save(any()); // no new question created yet — waiting for startNextSection
    }

    @Test
    void startNextSectionCreatesTheFirstQuestionOfTheFollowingSection() {
        InterviewSession session = newSession(2, 1, SessionStatus.IN_PROGRESS); // verbal target (1) already met
        Question answeredVerbal = newQuestion(session, 1, QuestionType.CONCEPTUAL);
        Question answeredMcq = newQuestion(session, 2, QuestionType.MCQ);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(answeredVerbal, answeredMcq));
        when(answerRepository.existsByQuestion(answeredMcq)).thenReturn(true);
        when(questionBank.countByType(Topic.DSA, QuestionType.MCQ)).thenReturn(1);
        when(questionBank.countByType(Topic.DSA, QuestionType.CODING)).thenReturn(1);
        // CODING is next, so createNextQuestion goes straight to the static bank — RAG (questionSelectionService)
        // is only ever consulted for the verbal/CONCEPTUAL section.
        when(questionBank.pickNext(eq(Topic.DSA), eq(Difficulty.EASY), eq(QuestionType.CODING), anySet()))
                .thenReturn(codingEntry("dsa-e1", Difficulty.EASY, "Two Sum"));
        when(questionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        QuestionResponse response = service.startNextSection(user, 1L);

        assertThat(response.questionType()).isEqualTo(QuestionType.CODING);
        assertThat(response.promptText()).isEqualTo("Two Sum");
        verifyNoInteractions(questionSelectionService);
    }

    @Test
    void startNextSectionRejectsWhenTheCurrentQuestionIsNotYetAnswered() {
        InterviewSession session = newSession(0, 1, SessionStatus.IN_PROGRESS);
        Question unanswered = newQuestion(session, 1, QuestionType.CONCEPTUAL);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(unanswered));
        when(answerRepository.existsByQuestion(unanswered)).thenReturn(false);

        assertThatThrownBy(() -> service.startNextSection(user, 1L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void runCodeRejectsANonCodingQuestion() {
        InterviewSession session = newSession(0, 1, SessionStatus.IN_PROGRESS);
        Question conceptual = newQuestion(session, 1, QuestionType.CONCEPTUAL);
        when(questionRepository.findById(1L)).thenReturn(Optional.of(conceptual));

        assertThatThrownBy(() -> service.runCode(user, 1L, new CodeRunRequest("java", "code")))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(codeExecutionService);
    }

    @Test
    void runCodeDelegatesToTheExecutionServiceForAValidCodingQuestion() {
        InterviewSession session = newSession(0, 1, SessionStatus.IN_PROGRESS);
        Question coding = newQuestion(session, 1, QuestionType.CODING);
        Question.TestCase testCase = new Question.TestCase();
        testCase.setInput("1 2");
        testCase.setExpectedOutput("3");
        coding.setTestCases(List.of(testCase));
        when(questionRepository.findById(1L)).thenReturn(Optional.of(coding));

        CodeRunResponse canned = new CodeRunResponse(List.of(), null);
        when(codeExecutionService.run(eq("java"), eq("code"), eq(coding.getTestCases()))).thenReturn(canned);

        CodeRunResponse response = service.runCode(user, 1L, new CodeRunRequest("java", "code"));

        assertThat(response).isSameAs(canned);
    }

    @Test
    void listSessionsIncludesTheOverallScoreOnlyForSessionsThatAlreadyHaveAReport() {
        InterviewSession completed = newSession(3, 3, SessionStatus.COMPLETED);
        completed.setId(1L);
        InterviewSession inProgress = newSession(1, 3, SessionStatus.IN_PROGRESS);
        inProgress.setId(2L);
        when(sessionRepository.findByUserOrderByCreatedAtDesc(user)).thenReturn(List.of(completed, inProgress));

        Report report = new Report();
        report.setSession(completed);
        report.setOverallScore(88);
        when(reportRepository.findBySessionIn(List.of(completed, inProgress))).thenReturn(List.of(report));

        List<SessionSummaryResponse> result = service.listSessions(user);

        assertThat(result).extracting(SessionSummaryResponse::overallScore).containsExactly(88, null);
    }

    @Test
    void getResumeStateReturnsTheLatestQuestionForAnInProgressSession() {
        // Lets the frontend reconstruct the interview after a page refresh (PLAN.md's known
        // "resuming a session isn't supported" gap) purely from the session id in the URL.
        InterviewSession session = newSession(1, 3, SessionStatus.IN_PROGRESS);
        Question latest = newQuestion(session, 2);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(newQuestion(session, 1), latest));
        when(answerRepository.existsByQuestion(latest)).thenReturn(false);

        SessionResumeResponse response = service.getResumeState(user, 1L);

        assertThat(response.status()).isEqualTo(SessionStatus.IN_PROGRESS);
        assertThat(response.progress().current()).isEqualTo(1);
        assertThat(response.progress().total()).isEqualTo(3);
        assertThat(response.currentQuestion()).isNotNull();
        assertThat(response.currentQuestion().sequenceNumber()).isEqualTo(2);
        assertThat(response.pendingSectionType()).isNull();
    }

    @Test
    void getResumeStateReturnsThePendingSectionWhenBetweenSections() {
        InterviewSession session = newSession(1, 1, SessionStatus.IN_PROGRESS); // verbal target already met
        Question answeredVerbal = newQuestion(session, 1, QuestionType.CONCEPTUAL);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(questionRepository.findBySessionOrderBySequenceNumberAsc(session)).thenReturn(List.of(answeredVerbal));
        when(answerRepository.existsByQuestion(answeredVerbal)).thenReturn(true);
        when(questionBank.countByType(Topic.DSA, QuestionType.MCQ)).thenReturn(2);
        when(questionBank.countByType(Topic.DSA, QuestionType.CODING)).thenReturn(0);

        SessionResumeResponse response = service.getResumeState(user, 1L);

        assertThat(response.currentQuestion()).isNull();
        assertThat(response.pendingSectionType()).isEqualTo(QuestionType.MCQ);
    }

    @Test
    void getResumeStateOmitsTheCurrentQuestionOnceTheSessionIsCompleted() {
        InterviewSession session = newSession(3, 3, SessionStatus.COMPLETED);

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        SessionResumeResponse response = service.getResumeState(user, 1L);

        assertThat(response.status()).isEqualTo(SessionStatus.COMPLETED);
        assertThat(response.currentQuestion()).isNull();
        verify(questionRepository, never()).findBySessionOrderBySequenceNumberAsc(any());
    }
}
