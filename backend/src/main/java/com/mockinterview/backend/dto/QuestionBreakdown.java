package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Correctness;
import com.mockinterview.backend.entity.Difficulty;

public record QuestionBreakdown(
        int sequenceNumber,
        String promptText,
        Difficulty difficulty,
        String answerText,
        int score,
        Correctness correctness,
        String feedback
) {
}
