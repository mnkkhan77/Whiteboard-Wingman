package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.PackFlashcard;
import com.mockinterview.backend.entity.ReviewQuality;

import java.time.LocalDateTime;

/**
 * The SM-2 spaced-repetition algorithm (SuperMemo 2, as popularized by Anki-style flashcard apps),
 * applied to one card's {@code easeFactor} / {@code intervalDays} / {@code repetitions} / {@code
 * dueAt}. Pure and stateless: given a card's current schedule and a review quality it returns the
 * next schedule, so it needs no persistence context of its own (PackFlashcardService applies it).
 *
 * Quality (ReviewQuality.score, 0-5): below 3 is a lapse — repetitions and interval reset to 1 day,
 * so the card comes back tomorrow. 3 and above advances it: 1 day after the first good review,
 * 6 days after the second, then interval * easeFactor each time after. The ease factor itself moves
 * by the standard SM-2 formula and is floored at 1.3 so a hard card never gets reviewed less often
 * than every ~1.3x its last interval.
 */
public final class Sm2Scheduler {

    private static final double MIN_EASE_FACTOR = 1.3;

    private Sm2Scheduler() {
    }

    /** Mutates {@code card} in place to its post-review schedule and returns it. */
    public static PackFlashcard review(PackFlashcard card, ReviewQuality quality, LocalDateTime now) {
        int q = quality.score();
        double ease = card.getEaseFactor() + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02));
        ease = Math.max(ease, MIN_EASE_FACTOR);

        int repetitions;
        int interval;
        if (q < 3) {
            repetitions = 0;
            interval = 1;
        } else {
            repetitions = card.getRepetitions() + 1;
            interval = switch (repetitions) {
                case 1 -> 1;
                case 2 -> 6;
                default -> (int) Math.round(card.getIntervalDays() * ease);
            };
            interval = Math.max(interval, 1);
        }

        card.setEaseFactor(ease);
        card.setRepetitions(repetitions);
        card.setIntervalDays(interval);
        card.setLastReviewedAt(now);
        card.setDueAt(now.plusDays(interval));
        return card;
    }
}
