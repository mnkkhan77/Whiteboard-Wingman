package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;

public record TopicRecommendationResponse(
        Topic topic,
        Difficulty startingDifficulty,
        String rationale
) {
}
