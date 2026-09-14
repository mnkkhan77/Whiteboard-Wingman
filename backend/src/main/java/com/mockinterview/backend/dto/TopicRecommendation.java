package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;

/**
 * Structured output contract for the LLM topic-recommendation call
 * (ChatClient.prompt(...).call().entity(TopicRecommendation.class)).
 * topicCode is a raw String rather than the Topic enum — with ~160 candidate codes, an LLM
 * hallucinating one outside the list is expected, and TopicRecommendationService must validate
 * (and retry) it rather than let Jackson enum-parsing throw here.
 */
public record TopicRecommendation(
        String topicCode,
        Difficulty startingDifficulty,
        String rationale
) {
}
