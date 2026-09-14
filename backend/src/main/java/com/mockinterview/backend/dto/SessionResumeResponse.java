package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.SessionStatus;
import com.mockinterview.backend.entity.Topic;

/**
 * Lets the frontend reconstruct an in-progress interview from just the session id in the URL
 * (e.g. after a page refresh) instead of relying solely on React Router's in-memory navigation
 * state, which is lost on reload. currentQuestion is null once the session is COMPLETED — the
 * frontend should navigate to the report page in that case instead of rendering a question form.
 * pendingSectionType is non-null when the session is paused between sections (the candidate
 * answered the last question of a section but hasn't started the next one yet) — currentQuestion
 * is null in that case too, and the frontend should show the "start next section" screen.
 * topic/topicsRemaining reflect the session's live (possibly already-advanced) state; a refresh
 * can't recover which numbered topic-of-N the candidate was on (that ordering only lives in the
 * frontend's own navigation state), just which topic is current and how many more are queued.
 */
public record SessionResumeResponse(
        SessionStatus status,
        ProgressResponse progress,
        QuestionResponse currentQuestion,
        QuestionType pendingSectionType,
        Topic topic,
        int topicsRemaining
) {
}
