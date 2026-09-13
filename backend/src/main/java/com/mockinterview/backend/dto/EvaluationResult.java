package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Correctness;
import com.mockinterview.backend.entity.DifficultyDelta;

import java.util.List;

/**
 * Structured output contract for the LLM evaluation call
 * (ChatClient.prompt(...).call().entity(EvaluationResult.class)).
 */
public record EvaluationResult(
        int score,
        Correctness correctness,
        String feedback,
        List<String> strengths,
        List<String> weaknesses,
        DifficultyDelta recommendedNextDifficulty
) {
}
