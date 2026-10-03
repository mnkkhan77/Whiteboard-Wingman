package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.flashcards.* — flashcard-deck generation for "flashcards from a pack"
 * (docs/study-packs-contract.md "Flashcards from a pack"). See PackFlashcardGenerator for how these
 * shape the LLM calls; mirrors QuizProperties.
 *
 * @param targetCards       deck size to aim for (contract: 30)
 * @param minCards          fewer valid cards than this and the deck is FAILED
 * @param maxBatches        LLM calls per deck; chunks are spread evenly across the document and
 *                          split over at most this many calls
 * @param chunksPerBatch    document chunks per call, so maxBatches * chunksPerBatch chunks are read
 * @param maxCardsPerBatch  cap per call: keeps each response well inside the output token budget
 *                          and makes a tiny document yield a few good cards rather than 30
 *                          near-duplicates
 * @param rateLimitRetries  retries of one call answered with HTTP 429 (Groq's per-minute limits)
 * @param rateLimitBackoff  wait before the first retry; doubled for each further one
 * @param generationThreads concurrent generation jobs (each is a few sequential LLM calls)
 * @param queueCapacity     jobs waiting for a thread; beyond it a request fails fast as busy
 */
@ConfigurationProperties(prefix = "app.flashcards")
public record FlashcardProperties(
        Integer targetCards,
        Integer minCards,
        Integer maxBatches,
        Integer chunksPerBatch,
        Integer maxCardsPerBatch,
        Integer rateLimitRetries,
        Duration rateLimitBackoff,
        Integer generationThreads,
        Integer queueCapacity
) {
    public FlashcardProperties {
        targetCards = targetCards == null ? 30 : targetCards;
        minCards = minCards == null ? 5 : minCards;
        maxBatches = maxBatches == null ? 4 : maxBatches;
        chunksPerBatch = chunksPerBatch == null ? 3 : chunksPerBatch;
        maxCardsPerBatch = maxCardsPerBatch == null ? 10 : maxCardsPerBatch;
        rateLimitRetries = rateLimitRetries == null ? 2 : rateLimitRetries;
        rateLimitBackoff = rateLimitBackoff == null ? Duration.ofSeconds(20) : rateLimitBackoff;
        generationThreads = generationThreads == null ? 2 : generationThreads;
        queueCapacity = queueCapacity == null ? 20 : queueCapacity;
    }

    public int maxChunks() {
        return maxBatches * chunksPerBatch;
    }
}
