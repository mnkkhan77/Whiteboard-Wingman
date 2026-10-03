package com.mockinterview.backend.service;

import com.mockinterview.backend.config.FlashcardProperties;
import com.mockinterview.backend.service.PackFlashcardGenerator.Batch;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** How a deck is split into chunks and LLM calls, with the default app.flashcards.* settings. */
class PackFlashcardGeneratorPlanTest {

    private final PackFlashcardGenerator generator = new PackFlashcardGenerator(
            null, null, null, null, null, null,
            new FlashcardProperties(null, null, null, null, null, null, null, null, null));

    private static List<SourceChunk> chunks(int n) {
        return IntStream.range(0, n).mapToObj(i -> new SourceChunk(i, "text " + i, i + 1, null, null)).toList();
    }

    @Test
    void twelveChunksMakeFourCallsOfThreeChunksAndEightCardsEach() {
        List<Batch> plan = generator.planBatches(chunks(12));

        assertThat(plan).hasSize(4).allSatisfy(b -> {
            assertThat(b.sources()).hasSize(3);
            assertThat(b.cardCount()).isEqualTo(8);
        });
        assertThat(plan.get(3).sources()).extracting(SourceChunk::chunkIndex).containsExactly(9, 10, 11);
    }

    @Test
    void aSmallDocumentGetsOneCallPerChunkCappedAtTenCardsEach() {
        List<Batch> two = generator.planBatches(chunks(2));
        assertThat(two).hasSize(2).allSatisfy(b -> {
            assertThat(b.sources()).hasSize(1);
            assertThat(b.cardCount()).isEqualTo(10);
        });

        List<Batch> six = generator.planBatches(chunks(6));
        assertThat(six).extracting(b -> b.sources().size()).containsExactly(1, 2, 1, 2);
        assertThat(six).allSatisfy(b -> assertThat(b.cardCount()).isEqualTo(8));
    }

    @Test
    void onlyAHttp429CountsAsRateLimited() {
        assertThat(PackFlashcardGenerator.isRateLimited(new NonTransientAiException("429 - rate_limit_exceeded"))).isTrue();
        assertThat(PackFlashcardGenerator.isRateLimited(new RuntimeException("wrapped",
                new NonTransientAiException("429 - slow down")))).isTrue();
        assertThat(PackFlashcardGenerator.isRateLimited(new NonTransientAiException("401 - invalid key"))).isFalse();
        assertThat(PackFlashcardGenerator.isRateLimited(new IllegalStateException("boom"))).isFalse();
    }
}
