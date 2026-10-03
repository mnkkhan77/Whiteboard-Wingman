package com.mockinterview.backend.entity;

/**
 * The four answer buttons a flashcard review offers, mapped to SM-2's 0-5 quality scale
 * (Sm2Scheduler) the way most SM-2 flashcard apps simplify it: AGAIN/HARD count as a lapse-risk
 * answer (AGAIN resets the card, HARD barely passes), GOOD/EASY both advance it, EASY more so.
 */
public enum ReviewQuality {
    AGAIN(0), HARD(3), GOOD(4), EASY(5);

    private final int score;

    ReviewQuality(int score) {
        this.score = score;
    }

    public int score() {
        return score;
    }
}
