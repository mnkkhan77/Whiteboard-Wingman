package com.mockinterview.backend.dto;

import java.util.List;

/** GET /api/packs/{id}/flashcards response: the whole deck plus how many cards are due now. */
public record FlashcardDeckDto(List<PackFlashcardDto> cards, long dueCount) {
}
