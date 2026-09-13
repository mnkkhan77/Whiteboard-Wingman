package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.QuestionType;

import java.util.List;

/**
 * One entry from the Phase 1 static question bank JSON files (src/main/resources/questions/*.json).
 * options/correctOptionIndex/explanation are MCQ-only; ioFormat/testCases are CODING-only (and
 * optional even there — a CODING question with no testCases just can't use the Run Code feature).
 * All of these are absent (null) in the JSON for CONCEPTUAL entries and deserialize to null.
 */
public record StaticQuestionEntry(
        String id,
        Difficulty difficulty,
        QuestionType questionType,
        String promptText,
        List<String> options,
        Integer correctOptionIndex,
        String explanation,
        String ioFormat,
        List<TestCase> testCases
) {
    public record TestCase(String input, String expectedOutput) {
    }
}
