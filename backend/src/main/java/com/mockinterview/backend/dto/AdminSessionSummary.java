package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.SessionStatus;
import com.mockinterview.backend.entity.Topic;

import java.time.LocalDateTime;

/** One row of a user's session history, used within GET /admin/users/{id} (PLAN.md §5). */
public record AdminSessionSummary(
        Long id,
        Topic topic,
        SessionStatus status,
        Difficulty startingDifficulty,
        Difficulty currentDifficulty,
        int questionsAsked,
        int targetQuestionCount,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        Integer overallScore
) {
}
