package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.DifficultyDelta;
import org.springframework.stereotype.Service;

/**
 * Rule-based, not ML — deterministic and easy to unit-test with a truth table (PLAN.md §6).
 * Deliberately a pure function: no repository access, no entity mutation. The caller
 * (InterviewSessionService) gathers the inputs and persists the result on InterviewSession.
 */
@Service
public class AdaptiveDifficultyService {

    private static final int STRONG_SCORE_THRESHOLD = 85;
    private static final int WEAK_SCORE_THRESHOLD = 35;
    private static final int STREAK_SCORE_THRESHOLD = 80;

    /**
     * @param currentDifficulty                                the difficulty the just-answered question was asked at
     * @param score                                             that answer's evaluation score (0-100)
     * @param modelSuggestion                                   the LLM's own recommendation for the next question
     * @param precedingQuestionAtSameDifficultyAlsoScoredHigh    true if the question immediately before this one was
     *                                                          also asked at currentDifficulty and also scored >= 80
     *                                                          — the "don't plateau" streak signal
     */
    public Difficulty computeNext(Difficulty currentDifficulty, int score, DifficultyDelta modelSuggestion,
                                   boolean precedingQuestionAtSameDifficultyAlsoScoredHigh) {
        int delta = switch (modelSuggestion) {
            case HARDER -> 1;
            case SAME -> 0;
            case EASIER -> -1;
        };

        // Guardrails: don't blindly trust the model's suggestion at the extremes.
        if (score >= STRONG_SCORE_THRESHOLD) {
            delta = Math.max(delta, 1);
        } else if (score <= WEAK_SCORE_THRESHOLD) {
            delta = Math.min(delta, -1);
        }

        // Two strong answers in a row at the same difficulty forces an increase, even if each
        // individually would have only said SAME — prevents plateauing at an easy difficulty.
        if (precedingQuestionAtSameDifficultyAlsoScoredHigh && score >= STREAK_SCORE_THRESHOLD) {
            delta = 1;
        }

        int clampedOrdinal = Math.max(0, Math.min(Difficulty.values().length - 1, currentDifficulty.ordinal() + delta));
        return Difficulty.values()[clampedOrdinal];
    }
}
