package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.SessionStatus;
import com.mockinterview.backend.entity.Topic;

/**
 * sectionComplete is true when the just-answered question was the last one in its section (verbal/
 * MCQ/coding) and another section follows — nextSectionType names it. The frontend then shows a
 * "take a break, start the next round when ready" screen instead of auto-advancing, and calls
 * POST /sessions/{id}/sections/next to fetch that section's first question.
 * nextTopic is non-null only when that upcoming section also belongs to a different (queued)
 * topic than the one just answered — i.e. a topic transition, not just a section transition
 * within the same topic — so the frontend can show which is happening.
 */
public record AnswerSubmitResponse(
        EvaluationResult evaluation,
        QuestionResponse nextQuestion,
        SessionStatus sessionStatus,
        ProgressResponse progress,
        boolean sectionComplete,
        QuestionType nextSectionType,
        Topic nextTopic
) {
}
