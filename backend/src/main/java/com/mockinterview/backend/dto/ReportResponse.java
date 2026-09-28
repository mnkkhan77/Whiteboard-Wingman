package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Topic;

import java.util.List;

/** packId/packTitle: only for a study-pack quiz (topic STUDY_PACK); the public shared report of
 *  one carries the title but not the id. */
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
        List<TopicBreakdown> topicBreakdown,
        Long packId,
        String packTitle
) {
}
