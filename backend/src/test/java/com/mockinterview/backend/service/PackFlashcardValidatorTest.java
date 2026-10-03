package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.GeneratedFlashcardBatch;
import com.mockinterview.backend.dto.GeneratedFlashcardBatch.Item;
import com.mockinterview.backend.entity.PackFlashcard;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackFlashcardValidatorTest {

    private static final List<SourceChunk> TWO_SOURCES = List.of(
            new SourceChunk(4, "ACID text", 3, 4, "Chapter 2 > Transactions"),
            new SourceChunk(9, "Locking text", 7, 7, null));

    private final PackFlashcardValidator validator = new PackFlashcardValidator(42L);

    @Test
    void validCardsAreAcceptedTrimmedAndKeepTheirSource() {
        List<PackFlashcard> accepted = validator.validate(new GeneratedFlashcardBatch(List.of(
                new Item("  What does the A in ACID stand for? ", " Atomicity: all or nothing. ", 1))),
                TWO_SOURCES);

        assertThat(accepted).hasSize(1);
        PackFlashcard card = accepted.get(0);
        assertThat(card.getPackId()).isEqualTo(42L);
        assertThat(card.getFront()).isEqualTo("What does the A in ACID stand for?");
        assertThat(card.getBack()).isEqualTo("Atomicity: all or nothing.");
        assertThat(card.getSourcePage()).isEqualTo(3);
        assertThat(card.getSourceSection()).isEqualTo("Chapter 2 > Transactions");
        assertThat(card.getSourceChunkIndex()).isEqualTo(4);
        assertThat(card.getDueAt()).isNotNull();
    }

    @Test
    void everyRuleViolationDropsOnlyThatCard() {
        List<Item> bad = Arrays.asList(
                null,
                new Item(null, "back", 1),
                new Item("  ", "back", 1),
                new Item("x".repeat(PackFlashcardValidator.MAX_FRONT_CHARS + 1), "back", 1),
                new Item("front", null, 1),
                new Item("front", "  ", 1),
                new Item("front", "x".repeat(PackFlashcardValidator.MAX_BACK_CHARS + 1), 1),
                new Item("Source out of range?", "back", 3),
                new Item("Source zero?", "back", 0),
                new Item("Missing source with two sources?", "back", null));

        assertThat(validator.validate(new GeneratedFlashcardBatch(bad), TWO_SOURCES)).isEmpty();
        assertThat(validator.validate(null, TWO_SOURCES)).isEmpty();
        assertThat(validator.validate(new GeneratedFlashcardBatch(null), TWO_SOURCES)).isEmpty();
    }

    @Test
    void duplicateFrontsAreDroppedAcrossBatchesIgnoringCaseSpacingAndPunctuation() {
        assertThat(validator.validate(new GeneratedFlashcardBatch(List.of(
                new Item("What is a deadlock?", "A cycle of waiting transactions.", 1))), TWO_SOURCES)).hasSize(1);

        List<PackFlashcard> second = validator.validate(new GeneratedFlashcardBatch(List.of(
                new Item("what is a   DEADLOCK", "dup", 2),
                new Item("What is a deadlock ?!", "dup", 1),
                new Item("How are deadlocks detected?", "Via a wait-for graph.", 2))), TWO_SOURCES);

        assertThat(second).extracting(PackFlashcard::getFront).containsExactly("How are deadlocks detected?");
    }

    @Test
    void theSourceNumberMayBeOmittedWhenTheBatchHadOneSource() {
        List<SourceChunk> one = List.of(new SourceChunk(0, "text", null, null, "Intro"));

        List<PackFlashcard> accepted = validator.validate(new GeneratedFlashcardBatch(List.of(
                new Item("What is WAL?", "A log written before data pages.", null))), one);

        assertThat(accepted).singleElement().satisfies(c -> {
            assertThat(c.getSourcePage()).isNull();
            assertThat(c.getSourceSection()).isEqualTo("Intro");
        });
    }
}
