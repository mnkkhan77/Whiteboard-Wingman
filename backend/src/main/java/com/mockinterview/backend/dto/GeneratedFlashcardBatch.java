package com.mockinterview.backend.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Structured-output contract for one flashcard-generation LLM call (PackFlashcardGenerator).
 */
public record GeneratedFlashcardBatch(List<Item> cards) {

    public record Item(
            @JsonPropertyDescription("The front of the card: a short question, term or prompt") String front,
            @JsonPropertyDescription("The back of the card: the answer or definition, 1-3 sentences") String back,
            @JsonPropertyDescription("The n of the source the card is based on") Integer sourceNumber
    ) {
    }
}
