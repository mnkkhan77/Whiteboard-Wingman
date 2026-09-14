package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Topic;

import java.time.LocalDateTime;

/** One point on a user's personal score trend — one per completed session with a report. */
public record ScorePoint(
        Long sessionId,
        Topic topic,
        LocalDateTime completedAt,
        int overallScore
) {
}
