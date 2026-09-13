package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;

import java.util.List;
import java.util.Map;

/** GET /admin/stats — platform-wide usage rollup (PLAN.md §5). */
public record AdminStats(
        long totalUsers,
        long totalSessions,
        Map<Topic, Long> sessionsByTopic,
        Map<Topic, Double> averageScoreByTopic,
        Map<Difficulty, Double> averageScoreByDifficulty,
        List<WeeklySignupCount> signupsOverTime
) {
}
