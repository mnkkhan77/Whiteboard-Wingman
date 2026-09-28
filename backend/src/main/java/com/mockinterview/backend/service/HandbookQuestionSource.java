package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.StaticQuestionEntry;
import com.mockinterview.backend.entity.Answer;
import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.Question;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Questions for handbook-topic interviews — the pre-Phase-4 behaviour, moved here unchanged from
 * InterviewSessionService. Verbal (CONCEPTUAL) questions come from the RAG-ingested handbook
 * content (QuestionSelectionService) whenever a match exists for the topic/difficulty, falling back
 * to the static bank; MCQ/CODING always come from the static bank (StaticQuestionBankService).
 *
 * Plan: verbal runs unless the session has no LLM key (nothing can grade it without one), sized by
 * the user's questionCount (RAG gives it a near-unlimited supply); MCQ/coding only run if the topic
 * has bank content for them, and then always use every bank question of that type.
 *
 * {@link #next} never returns empty: an exhausted static bank still throws IllegalStateException
 * (a 409), exactly as before.
 */
@Component
@RequiredArgsConstructor
public class HandbookQuestionSource implements QuestionSource {

    private final StaticQuestionBankService questionBank;
    private final QuestionSelectionService questionSelectionService;

    @Override
    public SectionPlan plan(InterviewSession session, Topic topic) {
        int coding = questionBank.countByType(topic, QuestionType.CODING);
        int mcq = questionBank.countByType(topic, QuestionType.MCQ);
        List<SectionPlan.Section> sections = new ArrayList<>();
        if (session.isLlmAvailable()) {
            sections.add(new SectionPlan.Section(QuestionType.CONCEPTUAL, session.getTargetQuestionCount()));
        }
        if (mcq > 0) {
            sections.add(new SectionPlan.Section(QuestionType.MCQ, mcq));
        }
        if (coding > 0) {
            sections.add(new SectionPlan.Section(QuestionType.CODING, coding));
        }
        return new SectionPlan(sections);
    }

    @Override
    public Optional<QuestionDraft> next(InterviewSession session, QuestionType section, Set<String> usedSourceIds) {
        StaticQuestionEntry entry = section == QuestionType.CONCEPTUAL
                ? questionSelectionService.pickNext(session.getTopic(), session.getCurrentDifficulty(), usedSourceIds)
                        .orElseGet(() -> questionBank.pickNext(session.getTopic(), session.getCurrentDifficulty(), QuestionType.CONCEPTUAL, usedSourceIds))
                : questionBank.pickNext(session.getTopic(), session.getCurrentDifficulty(), section, usedSourceIds);
        return Optional.of(QuestionDraft.of(entry));
    }

    /** Whether a keyless session on these topics has anything to ask (MCQ or coding content). */
    public boolean hasGradableContentWithoutLlm(List<Topic> topics) {
        return topics.stream().anyMatch(t ->
                questionBank.countByType(t, QuestionType.MCQ) > 0 || questionBank.countByType(t, QuestionType.CODING) > 0);
    }

    @Override
    public GradingPrompt gradingPrompt(Question question, Answer answer) {
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
        return new GradingPrompt(system, user);
    }
}
