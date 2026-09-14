package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Correctness;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;

public record QuestionBreakdown(
        int sequenceNumber,
        Topic topic,
        String promptText,
        Difficulty difficulty,
        String answerText,
        int score,
        Correctness correctness,
        String feedback
) {
}
