package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.PackFlashcard;

import java.time.LocalDateTime;

/** One flashcard with its SM-2 schedule (docs/study-packs-contract.md "Flashcards from a pack"). */
public record PackFlashcardDto(
        Long id,
        String front,
        String back,
        Integer sourcePage,
        String sourceSection,
        double easeFactor,
        int intervalDays,
        int repetitions,
        LocalDateTime dueAt,
        LocalDateTime lastReviewedAt
) {
    public static PackFlashcardDto from(PackFlashcard card) {
        return new PackFlashcardDto(
                card.getId(), card.getFront(), card.getBack(), card.getSourcePage(), card.getSourceSection(),
                card.getEaseFactor(), card.getIntervalDays(), card.getRepetitions(),
                card.getDueAt(), card.getLastReviewedAt());
    }
}
