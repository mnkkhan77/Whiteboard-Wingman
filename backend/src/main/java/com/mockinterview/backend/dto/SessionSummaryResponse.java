package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.SessionStatus;
import com.mockinterview.backend.entity.Topic;

import java.time.LocalDateTime;

public record SessionSummaryResponse(
        Long id,
        Topic topic,
        SessionStatus status,
        int questionsAsked,
        int targetQuestionCount,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        Integer overallScore,
        Long packId,
        String packTitle
) {
    public static SessionSummaryResponse from(InterviewSession s, Integer overallScore, PackRef pack) {
        return new SessionSummaryResponse(
                s.getId(), s.getTopic(), s.getStatus(),
                s.getQuestionsAsked(), s.getTargetQuestionCount(),
                s.getCreatedAt(), s.getCompletedAt(), overallScore,
                pack.packId(), pack.packTitle()
        );
    }
}
