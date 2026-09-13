package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.SessionStatus;

/**
 * Lets the frontend reconstruct an in-progress interview from just the session id in the URL
 * (e.g. after a page refresh) instead of relying solely on React Router's in-memory navigation
 * state, which is lost on reload. currentQuestion is null once the session is COMPLETED — the
 * frontend should navigate to the report page in that case instead of rendering a question form.
 * pendingSectionType is non-null when the session is paused between sections (the candidate
 * answered the last question of a section but hasn't started the next one yet) — currentQuestion
 * is null in that case too, and the frontend should show the "start next section" screen.
 */
public record SessionResumeResponse(
        SessionStatus status,
        ProgressResponse progress,
        QuestionResponse currentQuestion,
        QuestionType pendingSectionType
) {
}
