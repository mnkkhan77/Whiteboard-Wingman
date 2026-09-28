package com.mockinterview.backend.entity;

/**
 * A study pack's question-bank status (docs/study-packs-contract.md "Question bank"):
 * NONE -> GENERATING -> READY or FAILED; READY and FAILED can go back to GENERATING (regenerate /
 * retry). GENERATING is only ever entered through StudyPackRepository.startQuizGeneration, a
 * conditional update, so two simultaneous "generate" clicks can't start two jobs.
 */
public enum QuizStatus {
    NONE, GENERATING, READY, FAILED
}
