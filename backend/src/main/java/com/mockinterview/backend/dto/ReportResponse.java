package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Topic;

import java.util.List;

public record ReportResponse(
        Long sessionId,
        Topic topic,
        int overallScore,
        List<String> strongTopics,
        List<String> weakTopics,
        String summaryText,
        int questionCount,
        double averageDifficultyReached,
        List<QuestionBreakdown> breakdown,
        int tabSwitchCount,
        List<TopicBreakdown> topicBreakdown
) {
}
