package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.PackFlashcard;
import com.mockinterview.backend.entity.ReviewQuality;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** The SM-2 spaced-repetition math in isolation, with the standard scheduling textbook example. */
class Sm2SchedulerTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 10, 0);

    private static PackFlashcard freshCard() {
        PackFlashcard card = new PackFlashcard();
        card.setEaseFactor(2.5);
        card.setIntervalDays(0);
        card.setRepetitions(0);
        card.setDueAt(NOW);
        return card;
    }

    @Test
    void repeatedGoodReviewsFollowOneSixThenIntervalTimesEase() {
        PackFlashcard card = freshCard();

        Sm2Scheduler.review(card, ReviewQuality.GOOD, NOW);
        assertThat(card.getRepetitions()).isEqualTo(1);
        assertThat(card.getIntervalDays()).isEqualTo(1);
        assertThat(card.getDueAt()).isEqualTo(NOW.plusDays(1));

        Sm2Scheduler.review(card, ReviewQuality.GOOD, NOW);
        assertThat(card.getRepetitions()).isEqualTo(2);
        assertThat(card.getIntervalDays()).isEqualTo(6);

        double easeAfterTwoGood = card.getEaseFactor();
        Sm2Scheduler.review(card, ReviewQuality.GOOD, NOW);
        assertThat(card.getRepetitions()).isEqualTo(3);
        assertThat(card.getIntervalDays()).isEqualTo((int) Math.round(6 * easeAfterTwoGood));
        assertThat(card.getLastReviewedAt()).isEqualTo(NOW);
    }

    @Test
    void againResetsRepetitionsAndIntervalButNotBelowTheEaseFloor() {
        PackFlashcard card = freshCard();
        card.setEaseFactor(1.35);
        card.setRepetitions(4);
        card.setIntervalDays(20);

        Sm2Scheduler.review(card, ReviewQuality.AGAIN, NOW);

        assertThat(card.getRepetitions()).isZero();
        assertThat(card.getIntervalDays()).isEqualTo(1);
        assertThat(card.getDueAt()).isEqualTo(NOW.plusDays(1));
        assertThat(card.getEaseFactor()).isGreaterThanOrEqualTo(1.3);
    }

    @Test
    void easeFactorNeverDropsBelowOnePointThree() {
        PackFlashcard card = freshCard();
        card.setEaseFactor(1.3);

        for (int i = 0; i < 10; i++) {
            Sm2Scheduler.review(card, ReviewQuality.AGAIN, NOW);
        }

        assertThat(card.getEaseFactor()).isCloseTo(1.3, within(0.0001));
    }

    @Test
    void easierAnswersGrowTheEaseFactorHarderAnswersShrinkIt() {
        PackFlashcard easyPath = freshCard();
        PackFlashcard hardPath = freshCard();

        Sm2Scheduler.review(easyPath, ReviewQuality.EASY, NOW);
        Sm2Scheduler.review(hardPath, ReviewQuality.HARD, NOW);

        assertThat(easyPath.getEaseFactor()).isGreaterThan(2.5);
        assertThat(hardPath.getEaseFactor()).isLessThan(2.5);
    }
}
