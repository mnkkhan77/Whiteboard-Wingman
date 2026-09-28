package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.Answer;
import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.Question;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.Topic;

import java.util.Optional;
import java.util.Set;

/**
 * Where an interview session's questions come from — the one axis along which a handbook-topic
 * interview ({@link HandbookQuestionSource}) and a study-pack quiz ({@link PackQuestionSource})
 * differ, besides whose LLM key grades them ({@link SessionLlmResolver}). InterviewSessionService
 * keeps the shared flow (section order, "take a break" pauses, topic transitions, adaptive
 * difficulty, persistence) and asks the session's source for:
 * - the section plan of a topic,
 * - the next question of a section,
 * - how a free-text answer to one of its questions is to be graded.
 */
public interface QuestionSource {

    SectionPlan plan(InterviewSession session, Topic topic);

    /**
     * The next question of {@code section} for the session's current topic and difficulty, never
     * one whose sourceId is in {@code usedSourceIds}. Empty when the source has nothing left for
     * that section — InterviewSessionService then ends the section early.
     */
    Optional<QuestionDraft> next(InterviewSession session, QuestionType section, Set<String> usedSourceIds);

    /** The LLM prompt that grades a CONCEPTUAL (or, with a key, CODING) answer into an EvaluationResult. */
    GradingPrompt gradingPrompt(Question question, Answer answer);

    record GradingPrompt(String system, String user) {
    }
}
