package com.mockinterview.backend.service;

import com.mockinterview.backend.config.QuizProperties;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import com.mockinterview.backend.service.PackQuizGenerator.Batch;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** How a bank is split into chunks and LLM calls, with the default app.quiz.* settings. */
class PackQuizGeneratorPlanTest {

    private final PackQuizGenerator generator = new PackQuizGenerator(
            null, null, null, null, null, null,
            new QuizProperties(null, null, null, null, null, null, null, null, null, null, null));

    private static List<SourceChunk> chunks(int n) {
        return IntStream.range(0, n).mapToObj(i -> new SourceChunk(i, "text " + i, i + 1, null, null)).toList();
    }

    @Test
    void chunksAreSpreadEvenlyAcrossTheWholeDocument() {
        assertThat(PackQuizChunkReader.spreadIndices(120, 12))
                .containsExactly(5, 15, 25, 35, 45, 55, 65, 75, 85, 95, 105, 115);
        assertThat(PackQuizChunkReader.spreadIndices(13, 12)).doesNotHaveDuplicates().hasSize(12)
                .startsWith(0).endsWith(12);
        assertThat(PackQuizChunkReader.spreadIndices(5, 12)).containsExactly(0, 1, 2, 3, 4);
        assertThat(PackQuizChunkReader.spreadIndices(1, 12)).containsExactly(0);
    }

    @Test
    void twelveChunksMakeFourCallsOfThreeChunksAndSixQuestions() {
        List<Batch> plan = generator.planBatches(chunks(12));

        assertThat(plan).hasSize(4).allSatisfy(b -> {
            assertThat(b.sources()).hasSize(3);
            assertThat(b.mcq()).isEqualTo(3);
            assertThat(b.conceptual()).isEqualTo(3);
        });
        assertThat(plan.get(3).sources()).extracting(SourceChunk::chunkIndex).containsExactly(9, 10, 11);
    }

    @Test
    void aSmallDocumentGetsOneCallPerChunkCappedAtEightQuestionsEach() {
        List<Batch> two = generator.planBatches(chunks(2));
        assertThat(two).hasSize(2).allSatisfy(b -> {
            assertThat(b.sources()).hasSize(1);
            assertThat(b.mcq() + b.conceptual()).isEqualTo(8);
        });

        List<Batch> six = generator.planBatches(chunks(6));
        assertThat(six).extracting(b -> b.sources().size()).containsExactly(1, 2, 1, 2);
        assertThat(six).allSatisfy(b -> assertThat(b.mcq() + b.conceptual()).isEqualTo(6));
    }

    @Test
    void onlyAHttp429CountsAsRateLimited() {
        assertThat(PackQuizGenerator.isRateLimited(new NonTransientAiException("429 - rate_limit_exceeded"))).isTrue();
        assertThat(PackQuizGenerator.isRateLimited(new RuntimeException("wrapped",
                new NonTransientAiException("429 - slow down")))).isTrue();
        assertThat(PackQuizGenerator.isRateLimited(new NonTransientAiException("401 - invalid key"))).isFalse();
        assertThat(PackQuizGenerator.isRateLimited(new IllegalStateException("boom"))).isFalse();
    }
}
