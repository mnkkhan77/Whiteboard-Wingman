package com.mockinterview.backend.entity;

/**
 * A study pack's flashcard deck status (docs/study-packs-contract.md "Flashcards from a pack"):
 * NONE -> GENERATING -> READY or FAILED; READY and FAILED can go back to GENERATING (regenerate /
 * retry). GENERATING is only ever entered through StudyPackRepository.startFlashcardGeneration, a
 * conditional update, so two simultaneous "generate" clicks can't start two jobs.
 */
public enum FlashcardStatus {
    NONE, GENERATING, READY, FAILED
}
