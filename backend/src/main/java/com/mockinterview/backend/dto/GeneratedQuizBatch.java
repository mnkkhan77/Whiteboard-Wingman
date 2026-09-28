package com.mockinterview.backend.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Structured-output contract for one question-generation LLM call (PackQuizGenerator). type and
 * difficulty are plain strings on purpose: one question with a made-up enum value must be dropped
 * by PackQuizQuestionValidator, not fail the JSON parse of the whole batch.
 */
public record GeneratedQuizBatch(List<Item> questions) {

    public record Item(
            @JsonPropertyDescription("MCQ or CONCEPTUAL") String type,
            @JsonPropertyDescription("EASY, MEDIUM or HARD") String difficulty,
            @JsonPropertyDescription("The question text") String prompt,
            @JsonPropertyDescription("MCQ only: exactly 4 distinct answer choices, without letters or numbers in front")
            List<String> options,
            @JsonPropertyDescription("MCQ only: 0-based index of the one correct option") Integer correctOptionIndex,
            @JsonPropertyDescription("MCQ only: why the correct option is right, based on the source") String explanation,
            @JsonPropertyDescription("CONCEPTUAL only: a model answer of 2-4 sentences, based on the source")
            String referenceAnswer,
            @JsonPropertyDescription("The n of the source the question is based on") Integer sourceNumber
    ) {
    }
}
