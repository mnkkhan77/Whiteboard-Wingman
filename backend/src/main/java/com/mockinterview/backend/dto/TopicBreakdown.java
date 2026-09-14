package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Topic;

/** One topic's slice of a (possibly multi-topic) session's report — averageScore is the mean of
 *  just that topic's own evaluations, not the session-wide overallScore. */
public record TopicBreakdown(
        Topic topic,
        int averageScore,
        int questionCount
) {
}
