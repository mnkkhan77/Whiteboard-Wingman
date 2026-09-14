package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.*;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.exception.GuestAttemptLimitException;
import com.mockinterview.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The core "submit answer -> get next question" loop (PLAN.md §1).
 * Questions come from the RAG-ingested handbook content (QuestionSelectionService) whenever a
 * match exists for the session's topic/difficulty, falling back to the Phase 1 static bank
 * otherwise. Phase 3: currentDifficulty now adapts after every answer via
 * AdaptiveDifficultyService, instead of staying fixed at startingDifficulty for the whole session.
 *
 * A session is split into up to three sections, run in a fixed order: verbal (CONCEPTUAL), then
 * multiple choice (MCQ), then live coding (CODING) — each skipped entirely if the topic has no
 * questions of that type. The verbal section's size is the user's chosen questionCount (RAG gives
 * it a near-unlimited supply); the MCQ/coding sections always use every bank question of that type
 * for the topic. Sections don't auto-advance: submitAnswer reports sectionComplete/nextSectionType
 * instead of returning the next question, and the frontend must call startNextSection once the
 * candidate is ready — the deliberate "take a break between rounds" pause point.
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionService {

    private static final Logger log = LoggerFactory.getLogger(InterviewSessionService.class);

    private final InterviewSessionRepository sessionRepository;
    private final QuestionRepository questionRepository;
    private final AnswerRepository answerRepository;
    private final EvaluationRepository evaluationRepository;
    private final ReportRepository reportRepository;
    private final StaticQuestionBankService questionBank;
    private final QuestionSelectionService questionSelectionService;
    private final AdaptiveDifficultyService adaptiveDifficultyService;
    private final PerRequestChatClientFactory chatClientFactory;
    private final CodeExecutionService codeExecutionService;

    @Transactional
    public SessionStartResponse startSession(User user, StartSessionRequest request,
                                              String apiKey, PerRequestChatClientFactory.Provider provider, String model) {
        if (user.isGuest() && sessionRepository.existsByUser(user)) {
            throw new GuestAttemptLimitException();
        }

        int questionCount = request.questionCount() != null ? request.questionCount() : 8;
        boolean llmAvailable = apiKey != null && !apiKey.isBlank();
        List<Topic> topics = resolveTopics(request);

        InterviewSession session = new InterviewSession();
        session.setUser(user);
        session.setTopic(topics.get(0));
        session.setTopicQueue(new ArrayList<>(topics.subList(1, topics.size())));
        session.setStartingDifficulty(request.startingDifficulty());
        session.setCurrentDifficulty(request.startingDifficulty());
        session.setTargetQuestionCount(questionCount);
        session.setLlmAvailable(llmAvailable);

        if (llmAvailable) {
            // Fails fast on a missing/blank key. Building the client doesn't call the provider, so an
            // invalid-but-present key is only caught later, on the first real evaluate() call.
            chatClientFactory.forRequest(apiKey, provider, model);
        } else if (!hasGradableContentWithoutLlm(topics)) {
            throw new IllegalArgumentException(
                    "Without an API key, this topic has no multiple-choice or coding questions to practice — "
                            + "please provide a key, or pick a different topic.");
        }

        sessionRepository.save(session);

        Question firstQuestion = createNextQuestion(session);
        return new SessionStartResponse(session.getId(), QuestionResponse.from(firstQuestion));
    }

    @Transactional
    public AnswerSubmitResponse submitAnswer(User user, Long sessionId, SubmitAnswerRequest request,
                                              String apiKey, PerRequestChatClientFactory.Provider provider, String model) {
        InterviewSession session = getOwnedSession(user, sessionId);
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("Session is not in progress");
        }

        Question currentQuestion = questionRepository.findTopBySessionOrderBySequenceNumberDesc(session)
                .orElseThrow(() -> new IllegalStateException("Session has no questions"));

        boolean isMcq = currentQuestion.getQuestionType() == QuestionType.MCQ;
        boolean isCoding = currentQuestion.getQuestionType() == QuestionType.CODING;
        boolean hasAnswerText = request.answerText() != null && !request.answerText().isBlank();
        boolean hasCode = request.code() != null && !request.code().isBlank();
        boolean hasSelectedOption = request.selectedOptionIndex() != null;

        if (isMcq) {
            if (!hasSelectedOption) {
                throw new IllegalArgumentException("A selected option is required");
            }
        } else if (!hasAnswerText && !hasCode) {
            throw new IllegalArgumentException("An answer or code submission is required");
        }

        Answer answer = new Answer();
        answer.setQuestion(currentQuestion);
        answer.setAnswerText(isMcq ? describeSelectedOption(currentQuestion, request.selectedOptionIndex())
                : hasAnswerText ? request.answerText() : "");
        answer.setCodeSubmission(request.code());
        answerRepository.save(answer);

        EvaluationResult result;
        if (isMcq) {
            log.info("Evaluating MCQ answer (rule-based, no LLM call): sessionId={}, questionId={}", session.getId(), currentQuestion.getId());
            result = evaluateMcq(currentQuestion, request.selectedOptionIndex());
        } else if (isCoding && !session.isLlmAvailable()) {
            log.info("Evaluating coding answer from test results (no LLM key): sessionId={}, questionId={}", session.getId(), currentQuestion.getId());
            result = evaluateCodingWithoutLlm(currentQuestion, answer, request.language());
        } else {
            ChatClient chatClient = chatClientFactory.forRequest(apiKey, provider, model);
            log.info("Evaluating answer: sessionId={}, questionId={}, provider={}, model={}",
                    session.getId(), currentQuestion.getId(), provider, model);
            result = evaluate(chatClient, currentQuestion, answer);
        }

        // Gathered before saving the new evaluation, so this is purely the *prior* history.
        boolean precedingQuestionAtSameDifficultyAlsoScoredHigh = evaluationRepository
                .findBySessionOrderByQuestionSequence(session).stream()
                .reduce((first, second) -> second) // last element, i.e. the immediately preceding evaluation
                .filter(e -> e.getAnswer().getQuestion().getDifficulty() == currentQuestion.getDifficulty())
                .map(e -> e.getScore() >= 80)
                .orElse(false);

        Evaluation evaluation = new Evaluation();
        evaluation.setAnswer(answer);
        evaluation.setScore(result.score());
        evaluation.setCorrectness(result.correctness());
        evaluation.setFeedback(result.feedback());
        evaluation.setStrengths(result.strengths());
        evaluation.setWeaknesses(result.weaknesses());
        evaluation.setRecommendedNextDifficulty(result.recommendedNextDifficulty());
        evaluationRepository.save(evaluation);

        Difficulty nextDifficulty = adaptiveDifficultyService.computeNext(
                currentQuestion.getDifficulty(), result.score(), result.recommendedNextDifficulty(),
                precedingQuestionAtSameDifficultyAlsoScoredHigh);
        session.setCurrentDifficulty(nextDifficulty);

        session.setQuestionsAsked(session.getQuestionsAsked() + 1);

        // Scoped to the just-answered question's own topic — priorQuestions can span earlier,
        // already-exhausted topics in a multi-topic session, which must not count against this one.
        List<Question> askedSoFar = questionRepository.findBySessionOrderBySequenceNumberAsc(session);
        long askedInSection = askedSoFar.stream()
                .filter(q -> q.getQuestionType() == currentQuestion.getQuestionType() && q.getTopic() == currentQuestion.getTopic())
                .count();
        boolean sectionComplete = askedInSection >= sectionTarget(session, currentQuestion.getQuestionType());

        List<QuestionType> order = sectionOrder(session);
        int sectionIndex = order.indexOf(currentQuestion.getQuestionType());
        boolean hasNextSectionInTopic = sectionComplete && sectionIndex >= 0 && sectionIndex + 1 < order.size();

        QuestionResponse nextQuestionResponse = null;
        QuestionType nextSectionType = null;
        Topic nextTopic = null;
        boolean movesToNextSection;

        if (!sectionComplete) {
            Question nextQuestion = createNextQuestion(session);
            nextQuestionResponse = QuestionResponse.from(nextQuestion);
            movesToNextSection = false;
        } else if (hasNextSectionInTopic) {
            nextSectionType = order.get(sectionIndex + 1);
            movesToNextSection = true;
        } else if (advanceToNextRunnableTopic(session)) {
            nextTopic = session.getTopic();
            nextSectionType = sectionOrder(session).get(0);
            movesToNextSection = true;
        } else {
            session.setStatus(SessionStatus.COMPLETED);
            session.setCompletedAt(java.time.LocalDateTime.now());
            movesToNextSection = false;
        }
        sessionRepository.save(session);

        return new AnswerSubmitResponse(
                result,
                nextQuestionResponse,
                session.getStatus(),
                new ProgressResponse(session.getQuestionsAsked(), grandTotalQuestions(session, askedSoFar)),
                movesToNextSection,
                nextSectionType,
                nextTopic
        );
    }

    /** Called once the candidate is ready to move on from a completed section's "take a break" screen. */
    @Transactional
    public QuestionResponse startNextSection(User user, Long sessionId) {
        InterviewSession session = getOwnedSession(user, sessionId);
        if (session.getStatus() != SessionStatus.IN_PROGRESS) {
            throw new IllegalStateException("Session is not in progress");
        }

        List<Question> priorQuestions = questionRepository.findBySessionOrderBySequenceNumberAsc(session);
        if (!priorQuestions.isEmpty()) {
            Question latest = priorQuestions.get(priorQuestions.size() - 1);
            if (!answerRepository.existsByQuestion(latest)) {
                throw new IllegalStateException("Finish the current question before starting the next section");
            }
        }

        return QuestionResponse.from(createNextQuestion(session));
    }

    @Transactional(readOnly = true)
    public CodeRunResponse runCode(User user, Long questionId, CodeRunRequest request) {
        Question question = questionRepository.findById(questionId)
                .orElseThrow(() -> new IllegalArgumentException("Question not found"));
        if (!question.getSession().getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Question not found");
        }
        if (question.getQuestionType() != QuestionType.CODING) {
            throw new IllegalArgumentException("Run Code is only available for coding questions");
        }
        if (question.getTestCases().isEmpty()) {
            throw new IllegalArgumentException("No test cases are available for this question");
        }
        return codeExecutionService.run(request.language(), request.code(), question.getTestCases());
    }

    public List<SessionSummaryResponse> listSessions(User user) {
        List<InterviewSession> sessions = sessionRepository.findByUserOrderByCreatedAtDesc(user);
        Map<Long, Integer> scoresBySessionId = scoresBySessionId(sessions);
        return sessions.stream()
                .map(s -> SessionSummaryResponse.from(s, scoresBySessionId.get(s.getId())))
                .toList();
    }

    public SessionSummaryResponse getSession(User user, Long sessionId) {
        InterviewSession session = getOwnedSession(user, sessionId);
        Integer overallScore = reportRepository.findBySession(session).map(Report::getOverallScore).orElse(null);
        return SessionSummaryResponse.from(session, overallScore);
    }

    /** Reconstructs in-progress interview state from just the session id — lets the frontend
     *  resume after a page refresh instead of relying only on in-memory navigation state. */
    @Transactional(readOnly = true)
    public SessionResumeResponse getResumeState(User user, Long sessionId) {
        InterviewSession session = getOwnedSession(user, sessionId);
        QuestionResponse currentQuestion = null;
        QuestionType pendingSectionType = null;
        int total = grandTotalQuestions(session, List.of());

        if (session.getStatus() == SessionStatus.IN_PROGRESS) {
            List<Question> priorQuestions = questionRepository.findBySessionOrderBySequenceNumberAsc(session);
            total = grandTotalQuestions(session, priorQuestions);
            Question latest = priorQuestions.isEmpty() ? null : priorQuestions.get(priorQuestions.size() - 1);
            boolean latestAnswered = latest != null && answerRepository.existsByQuestion(latest);

            if (latest != null && !latestAnswered) {
                currentQuestion = QuestionResponse.from(latest);
            } else {
                pendingSectionType = resolveSectionType(session, priorQuestions);
            }
        }

        return new SessionResumeResponse(
                session.getStatus(),
                new ProgressResponse(session.getQuestionsAsked(), total),
                currentQuestion,
                pendingSectionType,
                session.getTopic(),
                session.getTopicQueue().size()
        );
    }

    private Map<Long, Integer> scoresBySessionId(List<InterviewSession> sessions) {
        Map<Long, Integer> scores = new HashMap<>();
        for (Report report : reportRepository.findBySessionIn(sessions)) {
            scores.put(report.getSession().getId(), report.getOverallScore());
        }
        return scores;
    }

    private Question createNextQuestion(InterviewSession session) {
        List<Question> priorQuestions = questionRepository.findBySessionOrderBySequenceNumberAsc(session);
        Set<String> usedIds = new HashSet<>();
        priorQuestions.forEach(q -> usedIds.add(q.getSourceChunkId()));

        QuestionType sectionType = resolveSectionType(session, priorQuestions);

        StaticQuestionEntry entry = sectionType == QuestionType.CONCEPTUAL
                ? questionSelectionService.pickNext(session.getTopic(), session.getCurrentDifficulty(), usedIds)
                        .orElseGet(() -> questionBank.pickNext(session.getTopic(), session.getCurrentDifficulty(), QuestionType.CONCEPTUAL, usedIds))
                : questionBank.pickNext(session.getTopic(), session.getCurrentDifficulty(), sectionType, usedIds);

        Question question = new Question();
        question.setSession(session);
        question.setSequenceNumber(priorQuestions.size() + 1);
        question.setTopic(session.getTopic());
        question.setDifficulty(entry.difficulty());
        question.setPromptText(entry.promptText());
        question.setQuestionType(entry.questionType());
        question.setSourceChunkId(entry.id());
        question.setOptions(entry.options() != null ? entry.options() : List.of());
        question.setCorrectOptionIndex(entry.correctOptionIndex());
        question.setExplanation(entry.explanation());
        question.setIoFormat(entry.ioFormat());
        question.setTestCases(toEntityTestCases(entry.testCases()));
        return questionRepository.save(question);
    }

    private List<Question.TestCase> toEntityTestCases(List<StaticQuestionEntry.TestCase> source) {
        if (source == null) {
            return List.of();
        }
        List<Question.TestCase> result = new ArrayList<>();
        for (StaticQuestionEntry.TestCase tc : source) {
            Question.TestCase entityTestCase = new Question.TestCase();
            entityTestCase.setInput(tc.input());
            entityTestCase.setExpectedOutput(tc.expectedOutput());
            result.add(entityTestCase);
        }
        return result;
    }

    /** Which section (verbal/MCQ/coding) the next-asked question should belong to, given how many
     *  of the *current topic's* questions of each type have been asked so far — priorQuestions may
     *  also contain earlier, already-exhausted topics in a multi-topic session, which must not
     *  count here. Throws if every section's target is already met for the current topic — that
     *  should be unreachable, since submitAnswer advances to the next queued topic (or marks the
     *  session COMPLETED) at that point instead. */
    private QuestionType resolveSectionType(InterviewSession session, List<Question> priorQuestions) {
        for (QuestionType type : sectionOrder(session)) {
            long askedInSection = priorQuestions.stream()
                    .filter(q -> q.getQuestionType() == type && q.getTopic() == session.getTopic())
                    .count();
            if (askedInSection < sectionTarget(session, type)) {
                return type;
            }
        }
        throw new IllegalStateException("All interview sections are already complete");
    }

    /** Verbal runs unless the session has no LLM key (nothing can grade it without one); MCQ/coding
     *  only run if the topic actually has bank content for them. */
    private List<QuestionType> sectionOrder(InterviewSession session, Topic topic) {
        List<QuestionType> order = new ArrayList<>();
        if (session.isLlmAvailable()) {
            order.add(QuestionType.CONCEPTUAL);
        }
        if (questionBank.countByType(topic, QuestionType.MCQ) > 0) {
            order.add(QuestionType.MCQ);
        }
        if (questionBank.countByType(topic, QuestionType.CODING) > 0) {
            order.add(QuestionType.CODING);
        }
        return order;
    }

    private List<QuestionType> sectionOrder(InterviewSession session) {
        return sectionOrder(session, session.getTopic());
    }

    private int sectionTarget(InterviewSession session, Topic topic, QuestionType type) {
        return switch (type) {
            case CONCEPTUAL -> session.getTargetQuestionCount();
            case MCQ -> questionBank.countByType(topic, QuestionType.MCQ);
            case CODING -> questionBank.countByType(topic, QuestionType.CODING);
        };
    }

    private int sectionTarget(InterviewSession session, QuestionType type) {
        return sectionTarget(session, session.getTopic(), type);
    }

    /** Sums every topic's section targets — topics already asked from (per Question.topic, which
     *  keeps its value even after session.topic itself has moved on), the current topic, and any
     *  still queued — so the progress bar's total is both accurate from the very first question of
     *  a multi-topic session and stays stable as topics complete, instead of shrinking each time.
     *  priorQuestions may be passed as an empty list (e.g. a resumed session that isn't
     *  IN_PROGRESS, where nothing needs querying) at the cost of not counting topics already
     *  exhausted in that one case. */
    private int grandTotalQuestions(InterviewSession session, List<Question> priorQuestions) {
        List<Topic> allTopics = new ArrayList<>();
        priorQuestions.stream().map(Question::getTopic).forEach(t -> {
            if (!allTopics.contains(t)) {
                allTopics.add(t);
            }
        });
        if (!allTopics.contains(session.getTopic())) {
            allTopics.add(session.getTopic());
        }
        for (Topic t : session.getTopicQueue()) {
            if (!allTopics.contains(t)) {
                allTopics.add(t);
            }
        }
        return allTopics.stream()
                .mapToInt(t -> sectionOrder(session, t).stream().mapToInt(type -> sectionTarget(session, t, type)).sum())
                .sum();
    }

    private List<Topic> resolveTopics(StartSessionRequest request) {
        if (request.topics() != null && request.topics().size() >= 2) {
            return request.topics();
        }
        return List.of(request.topic());
    }

    private boolean hasGradableContentWithoutLlm(List<Topic> topics) {
        return topics.stream().anyMatch(t ->
                questionBank.countByType(t, QuestionType.MCQ) > 0 || questionBank.countByType(t, QuestionType.CODING) > 0);
    }

    /** Pops queued topics into session.topic — resetting currentDifficulty back to
     *  startingDifficulty each time, so a fresh topic doesn't inherit unrelated difficulty drift
     *  from the one before it — until one has at least one runnable section, or the queue runs
     *  out. Returns false, meaning the whole session is now done, only once every queued topic
     *  (as well as the current one) has been exhausted. */
    private boolean advanceToNextRunnableTopic(InterviewSession session) {
        List<Topic> queue = session.getTopicQueue();
        while (!queue.isEmpty()) {
            Topic next = queue.remove(0);
            session.setTopic(next);
            session.setCurrentDifficulty(session.getStartingDifficulty());
            if (!sectionOrder(session).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private String describeSelectedOption(Question question, int selectedOptionIndex) {
        List<String> options = question.getOptions();
        String optionText = selectedOptionIndex >= 0 && selectedOptionIndex < options.size()
                ? options.get(selectedOptionIndex)
                : "(invalid option)";
        return "Selected option " + (selectedOptionIndex + 1) + ": " + optionText;
    }

    /** MCQ correctness is objective, so this is scored deterministically — no LLM call needed. */
    private EvaluationResult evaluateMcq(Question question, int selectedOptionIndex) {
        boolean correct = question.getCorrectOptionIndex() != null && selectedOptionIndex == question.getCorrectOptionIndex();
        String explanation = question.getExplanation() != null ? question.getExplanation() : "";

        String feedback;
        if (correct) {
            feedback = ("Correct! " + explanation).trim();
        } else {
            String correctOptionText = question.getCorrectOptionIndex() != null && question.getCorrectOptionIndex() < question.getOptions().size()
                    ? question.getOptions().get(question.getCorrectOptionIndex())
                    : "unknown";
            feedback = ("Not quite — the correct answer was \"" + correctOptionText + "\". " + explanation).trim();
        }

        return new EvaluationResult(
                correct ? 100 : 0,
                correct ? Correctness.CORRECT : Correctness.INCORRECT,
                feedback,
                List.of(),
                correct || explanation.isBlank() ? List.of() : List.of(explanation),
                correct ? DifficultyDelta.HARDER : DifficultyDelta.EASIER
        );
    }

    /** No LLM key — grade purely from the question's stored test cases (via Judge0), same signal
     *  "Run Code" already shows the candidate, just re-checked server-side at submit time. */
    private EvaluationResult evaluateCodingWithoutLlm(Question question, Answer answer, String language) {
        List<Question.TestCase> testCases = question.getTestCases();
        if (testCases.isEmpty()) {
            return new EvaluationResult(0, Correctness.INCORRECT,
                    "This question has no test cases to grade against, and no LLM key was provided to review it another way.",
                    List.of(), List.of(), DifficultyDelta.SAME);
        }
        if (language == null || language.isBlank()) {
            throw new IllegalArgumentException("A language is required to grade a coding answer without an LLM key");
        }

        CodeRunResponse runResult = codeExecutionService.run(language, answer.getCodeSubmission() != null ? answer.getCodeSubmission() : "", testCases);

        if (runResult.compileError() != null) {
            return new EvaluationResult(0, Correctness.INCORRECT,
                    "Compile error (graded from test results — no LLM key was provided):\n" + runResult.compileError(),
                    List.of(), List.of(), DifficultyDelta.EASIER);
        }

        long passed = runResult.results().stream().filter(CodeRunResponse.TestCaseResult::passed).count();
        int total = runResult.results().size();
        int score = total > 0 ? (int) Math.round(100.0 * passed / total) : 0;
        Correctness correctness = passed == total ? Correctness.CORRECT : passed > 0 ? Correctness.PARTIALLY_CORRECT : Correctness.INCORRECT;
        DifficultyDelta delta = passed == total ? DifficultyDelta.HARDER : passed == 0 ? DifficultyDelta.EASIER : DifficultyDelta.SAME;
        String feedback = "Passed %d of %d test cases (graded from test results — no LLM key was provided for narrative feedback)."
                .formatted(passed, total);

        return new EvaluationResult(score, correctness, feedback, List.of(), List.of(), delta);
    }

    private EvaluationResult evaluate(ChatClient chatClient, Question question, Answer answer) {
        String system = """
                You are an expert technical interviewer evaluating a candidate's answer during a mock interview.
                Be fair but rigorous. Return your evaluation using the required structured format only.
                """;

        String user = """
                Question (%s, %s difficulty):
                %s

                %s

                %s

                Score the answer 0-100, classify its correctness, give concise actionable feedback,
                list specific strengths and weaknesses, and recommend whether the next question should
                be easier, the same, or harder.
                """.formatted(
                question.getQuestionType(), question.getDifficulty(), question.getPromptText(),
                answer.getAnswerText() != null && !answer.getAnswerText().isBlank()
                        ? "Candidate's answer:\n" + answer.getAnswerText()
                        : "",
                answer.getCodeSubmission() != null && !answer.getCodeSubmission().isBlank()
                        ? "Candidate's code submission:\n" + answer.getCodeSubmission()
                        : ""
        );

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(EvaluationResult.class);
    }

    InterviewSession getOwnedSession(User user, Long sessionId) {
        InterviewSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (!session.getUser().getId().equals(user.getId())) {
            throw new IllegalArgumentException("Session not found");
        }
        return session;
    }
}
