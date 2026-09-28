package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.PackQuizQuestion;
import com.mockinterview.backend.entity.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure parts of pack-quiz question selection: the section split and closest-difficulty pick. */
class PackQuestionSourceTest {

    private static PackQuizQuestion bank(long id, Difficulty difficulty) {
        PackQuizQuestion q = new PackQuizQuestion();
        q.setId(id);
        q.setQuestionType(QuestionType.CONCEPTUAL);
        q.setDifficulty(difficulty);
        return q;
    }

    @Test
    void questionCountIsSplitConceptualCeilThenMcqFloor() {
        assertThat(PackQuestionSource.split(8, 12, 12).sections()).containsExactly(
                new SectionPlan.Section(QuestionType.CONCEPTUAL, 4), new SectionPlan.Section(QuestionType.MCQ, 4));
        assertThat(PackQuestionSource.split(5, 12, 12).sections()).containsExactly(
                new SectionPlan.Section(QuestionType.CONCEPTUAL, 3), new SectionPlan.Section(QuestionType.MCQ, 2));
        assertThat(PackQuestionSource.split(2, 12, 12).total()).isEqualTo(2);
    }

    @Test
    void eachSectionIsCappedByWhatTheBankHasAndAnEmptySectionIsSkipped() {
        SectionPlan capped = PackQuestionSource.split(20, 3, 12);
        assertThat(capped.target(QuestionType.CONCEPTUAL)).isEqualTo(3);
        assertThat(capped.target(QuestionType.MCQ)).isEqualTo(10);

        SectionPlan mcqOnly = PackQuestionSource.split(6, 0, 2);
        assertThat(mcqOnly.order()).containsExactly(QuestionType.MCQ);
        assertThat(mcqOnly.target(QuestionType.MCQ)).isEqualTo(2);

        assertThat(PackQuestionSource.split(6, 0, 0).isEmpty()).isTrue();
    }

    @Test
    void picksTheClosestDifficultyAndBreaksTiesByBankOrder() {
        List<PackQuizQuestion> candidates = List.of(bank(1, Difficulty.EASY), bank(2, Difficulty.HARD), bank(3, Difficulty.MEDIUM));

        assertThat(PackQuestionSource.closest(candidates, Difficulty.MEDIUM, Set.of())).get()
                .extracting(PackQuizQuestion::getId).isEqualTo(3L);
        assertThat(PackQuestionSource.closest(candidates, Difficulty.HARD, Set.of())).get()
                .extracting(PackQuizQuestion::getId).isEqualTo(2L);
        // MEDIUM used: EASY and HARD are both one step away -> the earlier bank question wins.
        assertThat(PackQuestionSource.closest(candidates, Difficulty.MEDIUM, Set.of("packq-3"))).get()
                .extracting(PackQuizQuestion::getId).isEqualTo(1L);
        // Only HARD left for an EASY session: still served — the closest that exists.
        assertThat(PackQuestionSource.closest(candidates, Difficulty.EASY, Set.of("packq-1", "packq-3"))).get()
                .extracting(PackQuizQuestion::getId).isEqualTo(2L);
    }

    @Test
    void neverRepeatsAndIsEmptyOnceEverythingWasAsked() {
        List<PackQuizQuestion> candidates = List.of(bank(1, Difficulty.EASY), bank(2, Difficulty.EASY));

        assertThat(PackQuestionSource.closest(candidates, Difficulty.EASY, Set.of("packq-1"))).get()
                .extracting(PackQuizQuestion::getId).isEqualTo(2L);
        assertThat(PackQuestionSource.closest(candidates, Difficulty.EASY, Set.of("packq-1", "packq-2"))).isEmpty();
    }
}
