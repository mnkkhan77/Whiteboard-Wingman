package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.quiz.* — question-bank generation for "quiz from a pack" (docs/study-packs-contract.md
 * "Question bank"). See PackQuizGenerator for how these shape the LLM calls.
 *
 * @param targetQuestions     bank size to aim for, split half MCQ / half CONCEPTUAL (contract: 24)
 * @param minQuestions        fewer valid questions than this and the bank is FAILED — the smallest
 *                            quiz asks 2
 * @param maxBatches          LLM calls per bank; chunks are spread evenly across the document and
 *                            split over at most this many calls
 * @param chunksPerBatch      document chunks per call, so maxBatches * chunksPerBatch chunks are read
 * @param maxQuestionsPerBatch cap per call: keeps each response well inside the output token budget
 *                            and makes a tiny document yield a few good questions rather than 24
 *                            near-duplicates
 * @param rateLimitRetries    retries of one call answered with HTTP 429 (Groq's per-minute limits)
 * @param rateLimitBackoff    wait before the first retry; doubled for each further one
 * @param sessionRateLimitRetries retries of a 429 while grading / writing a pack quiz's report —
 *                            short, since a user request is waiting on it
 * @param sessionRateLimitBackoff wait before that retry
 * @param generationThreads   concurrent generation jobs (each is a few sequential LLM calls)
 * @param queueCapacity       jobs waiting for a thread; beyond it a request fails fast as busy
 */
@ConfigurationProperties(prefix = "app.quiz")
public record QuizProperties(
        Integer targetQuestions,
        Integer minQuestions,
        Integer maxBatches,
        Integer chunksPerBatch,
        Integer maxQuestionsPerBatch,
        Integer rateLimitRetries,
        Duration rateLimitBackoff,
        Integer generationThreads,
        Integer queueCapacity,
        Integer sessionRateLimitRetries,
        Duration sessionRateLimitBackoff
) {
    public QuizProperties {
        targetQuestions = targetQuestions == null ? 24 : targetQuestions;
        minQuestions = minQuestions == null ? 2 : minQuestions;
        maxBatches = maxBatches == null ? 4 : maxBatches;
        chunksPerBatch = chunksPerBatch == null ? 3 : chunksPerBatch;
        maxQuestionsPerBatch = maxQuestionsPerBatch == null ? 8 : maxQuestionsPerBatch;
        rateLimitRetries = rateLimitRetries == null ? 2 : rateLimitRetries;
        rateLimitBackoff = rateLimitBackoff == null ? Duration.ofSeconds(20) : rateLimitBackoff;
        generationThreads = generationThreads == null ? 2 : generationThreads;
        queueCapacity = queueCapacity == null ? 20 : queueCapacity;
        sessionRateLimitRetries = sessionRateLimitRetries == null ? 1 : sessionRateLimitRetries;
        sessionRateLimitBackoff = sessionRateLimitBackoff == null ? Duration.ofSeconds(5) : sessionRateLimitBackoff;
    }

    public int maxChunks() {
        return maxBatches * chunksPerBatch;
    }
}
