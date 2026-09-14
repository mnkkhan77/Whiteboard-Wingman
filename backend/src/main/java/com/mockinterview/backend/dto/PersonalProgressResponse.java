package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Topic;

import java.util.List;
import java.util.Map;

/** GET /sessions/progress — a signed-in user's own score trend and per-topic averages. */
public record PersonalProgressResponse(
        List<ScorePoint> scoreTrend,
        Map<Topic, Double> averageScoreByTopic,
        int totalSessions,
        int completedSessions,
        Double overallAverageScore
) {
}
