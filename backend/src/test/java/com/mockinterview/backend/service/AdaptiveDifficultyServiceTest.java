package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.DifficultyDelta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveDifficultyServiceTest {

    private final AdaptiveDifficultyService service = new AdaptiveDifficultyService();

    @ParameterizedTest(name = "{0} + score {1} + model says {2} + streak={3} -> {4}")
    @CsvSource({
            // Base case: trust the model's own suggestion when the score is in the "normal" range.
            "MEDIUM, 60, HARDER, false, HARD",
            "MEDIUM, 60, SAME,   false, MEDIUM",
            "MEDIUM, 60, EASIER, false, EASY",

            // Guardrail: a very strong score (>=85) never allows a decrease or a stay, even if
            // the model said SAME or (implausibly) EASIER.
            "MEDIUM, 90, SAME,   false, HARD",
            "MEDIUM, 90, EASIER, false, HARD",
            "MEDIUM, 90, HARDER, false, HARD",

            // Guardrail: a very weak score (<=35) never allows an increase or a stay.
            "MEDIUM, 20, SAME,   false, EASY",
            "MEDIUM, 20, HARDER, false, EASY",
            "MEDIUM, 20, EASIER, false, EASY",

            // Streak rule: forces +1 even on a plain SAME suggestion, as long as score >= 80.
            "EASY,   80, SAME,   true,  MEDIUM",
            "MEDIUM, 82, SAME,   true,  HARD",

            // Streak flag alone isn't enough — the current score must also clear the 80 threshold.
            "EASY,   79, SAME,   true,  EASY",

            // Clamping at the boundaries: can't go below EASY or above HARD.
            "EASY,   20, EASIER, false, EASY",
            "HARD,   90, HARDER, false, HARD",
    })
    void followsTheDocumentedTruthTable(Difficulty current, int score, DifficultyDelta suggestion,
                                          boolean streak, Difficulty expected) {
        assertThat(service.computeNext(current, score, suggestion, streak)).isEqualTo(expected);
    }

    @Test
    void theStrongScoreGuardrailWinsOverAnExplicitEasierSuggestion() {
        // Regression-style check for the exact scenario called out in PLAN.md §6: the model
        // suggesting EASIER on a 90+ score is treated as an inconsistency, not honored.
        assertThat(service.computeNext(Difficulty.EASY, 95, DifficultyDelta.EASIER, false))
                .isEqualTo(Difficulty.MEDIUM);
    }

    @Test
    void theWeakScoreGuardrailWinsOverAnExplicitHarderSuggestion() {
        assertThat(service.computeNext(Difficulty.HARD, 10, DifficultyDelta.HARDER, false))
                .isEqualTo(Difficulty.MEDIUM);
    }
}
