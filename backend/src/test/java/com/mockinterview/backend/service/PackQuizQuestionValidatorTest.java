package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.GeneratedQuizBatch;
import com.mockinterview.backend.dto.GeneratedQuizBatch.Item;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.PackQuizQuestion;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackQuizQuestionValidatorTest {

    private static final List<SourceChunk> TWO_SOURCES = List.of(
            new SourceChunk(4, "ACID text", 3, 4, "Chapter 2 > Transactions"),
            new SourceChunk(9, "Locking text", 7, 7, null));

    private final PackQuizQuestionValidator validator = new PackQuizQuestionValidator(42L);

    private static Item mcq(String prompt, List<String> options, Integer correct, String explanation, Integer source) {
        return new Item("MCQ", "EASY", prompt, options, correct, explanation, null, source);
    }

    private static Item conceptual(String prompt, String difficulty, String reference, Integer source) {
        return new Item("CONCEPTUAL", difficulty, prompt, null, null, null, reference, source);
    }

    private static final List<String> FOUR = List.of("Atomicity", "Availability", "Accuracy", "Agility");

    @Test
    void validQuestionsAreAcceptedTrimmedAndKeepTheirSource() {
        List<PackQuizQuestion> accepted = validator.validate(new GeneratedQuizBatch(List.of(
                new Item(" mcq ", " easy ", "  What does the A in ACID stand for? ",
                        List.of(" Atomicity ", "Availability", "Accuracy", "Agility"), 0, " All or nothing. ",
                        "ignored for MCQ", 1),
                conceptual("Why is strict 2PL recoverable?", "hard", " It holds write locks until commit. ", 2))),
                TWO_SOURCES);

        assertThat(accepted).hasSize(2);
        PackQuizQuestion m = accepted.get(0);
        assertThat(m.getPackId()).isEqualTo(42L);
        assertThat(m.getQuestionType()).isEqualTo(QuestionType.MCQ);
        assertThat(m.getDifficulty()).isEqualTo(Difficulty.EASY);
        assertThat(m.getPrompt()).isEqualTo("What does the A in ACID stand for?");
        assertThat(m.getOptions()).containsExactly("Atomicity", "Availability", "Accuracy", "Agility");
        assertThat(m.getCorrectOptionIndex()).isZero();
        assertThat(m.getExplanation()).isEqualTo("All or nothing.");
        assertThat(m.getReferenceAnswer()).isNull();
        assertThat(m.getSourcePage()).isEqualTo(3);
        assertThat(m.getSourceSection()).isEqualTo("Chapter 2 > Transactions");
        assertThat(m.getSourceChunkIndex()).isEqualTo(4);

        PackQuizQuestion c = accepted.get(1);
        assertThat(c.getQuestionType()).isEqualTo(QuestionType.CONCEPTUAL);
        assertThat(c.getDifficulty()).isEqualTo(Difficulty.HARD);
        assertThat(c.getReferenceAnswer()).isEqualTo("It holds write locks until commit.");
        assertThat(c.getOptions()).isNull();
        assertThat(c.getSourcePage()).isEqualTo(7);
        assertThat(c.getSourceChunkIndex()).isEqualTo(9);
    }

    @Test
    void everyRuleViolationDropsOnlyThatQuestion() {
        List<Item> bad = Arrays.asList(
                null,
                mcq("Three options?", List.of("a", "b", "c"), 0, "x", 1),
                mcq("Five options?", List.of("a", "b", "c", "d", "e"), 0, "x", 1),
                mcq("Blank option?", List.of("a", " ", "c", "d"), 0, "x", 1),
                mcq("Same option twice?", List.of("Read committed", "read  COMMITTED", "c", "d"), 0, "x", 1),
                mcq("Option too long?", List.of("a", "b", "c", "x".repeat(PackQuizQuestionValidator.MAX_OPTION_CHARS + 1)), 0, "x", 1),
                mcq("Index out of range?", FOUR, 4, "x", 1),
                mcq("Negative index?", FOUR, -1, "x", 1),
                mcq("No index?", FOUR, null, "x", 1),
                mcq("No explanation?", FOUR, 0, "  ", 1),
                mcq("No options?", null, 0, "x", 1),
                conceptual("No reference?", "EASY", "", 1),
                new Item("CODING", "EASY", "Write a function", null, null, null, "ref", 1),
                new Item("ESSAY", "EASY", "Unknown type", null, null, null, "ref", 1),
                conceptual("Bad difficulty?", "EXTREME", "ref", 1),
                conceptual("No difficulty?", null, "ref", 1),
                conceptual("   ", "EASY", "ref", 1),
                conceptual("x".repeat(PackQuizQuestionValidator.MAX_PROMPT_CHARS + 1), "EASY", "ref", 1),
                conceptual("Source out of range?", "EASY", "ref", 3),
                conceptual("Source zero?", "EASY", "ref", 0),
                conceptual("Missing source with two sources?", "EASY", "ref", null));

        assertThat(validator.validate(new GeneratedQuizBatch(bad), TWO_SOURCES)).isEmpty();
        assertThat(validator.validate(null, TWO_SOURCES)).isEmpty();
        assertThat(validator.validate(new GeneratedQuizBatch(null), TWO_SOURCES)).isEmpty();
    }

    @Test
    void duplicatePromptsAreDroppedAcrossBatchesIgnoringCaseSpacingAndPunctuation() {
        assertThat(validator.validate(new GeneratedQuizBatch(List.of(
                conceptual("What is a deadlock?", "EASY", "ref", 1))), TWO_SOURCES)).hasSize(1);

        List<PackQuizQuestion> second = validator.validate(new GeneratedQuizBatch(List.of(
                conceptual("what is a   DEADLOCK", "MEDIUM", "ref", 2),
                mcq("What is a deadlock ?!", FOUR, 1, "x", 1),
                conceptual("How are deadlocks detected?", "MEDIUM", "ref", 2))), TWO_SOURCES);

        assertThat(second).extracting(PackQuizQuestion::getPrompt).containsExactly("How are deadlocks detected?");
    }

    @Test
    void anInvalidQuestionDoesNotBlockALaterValidOneWithTheSamePrompt() {
        List<PackQuizQuestion> accepted = validator.validate(new GeneratedQuizBatch(List.of(
                mcq("What is MVCC?", List.of("a", "b"), 0, "x", 1),
                conceptual("What is MVCC?", "EASY", "Several row versions.", 1))), TWO_SOURCES);

        assertThat(accepted).extracting(PackQuizQuestion::getQuestionType).containsExactly(QuestionType.CONCEPTUAL);
    }

    @Test
    void theSourceNumberMayBeOmittedWhenTheBatchHadOneSource() {
        List<SourceChunk> one = List.of(new SourceChunk(0, "text", null, null, "Intro"));

        List<PackQuizQuestion> accepted = validator.validate(new GeneratedQuizBatch(List.of(
                conceptual("What is WAL?", "EASY", "A log written before data pages.", null))), one);

        assertThat(accepted).singleElement().satisfies(q -> {
            assertThat(q.getSourcePage()).isNull();
            assertThat(q.getSourceSection()).isEqualTo("Intro");
        });
    }
}
