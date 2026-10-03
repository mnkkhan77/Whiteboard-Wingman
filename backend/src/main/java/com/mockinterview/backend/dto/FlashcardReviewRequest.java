package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.ReviewQuality;
import jakarta.validation.constraints.NotNull;

/** POST /api/packs/{id}/flashcards/{cardId}/review body. */
public record FlashcardReviewRequest(@NotNull ReviewQuality quality) {
}
