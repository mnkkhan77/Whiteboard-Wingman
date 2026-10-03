package com.mockinterview.backend.service;

import com.mockinterview.backend.config.FlashcardProperties;
import com.mockinterview.backend.dto.GeneratedFlashcardBatch;
import com.mockinterview.backend.entity.PackFlashcard;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The flashcard-deck generation job (docs/study-packs-contract.md "Flashcards from a pack"), run on
 * FlashcardGenerationExecutor for a pack PackFlashcardService has just moved to flashcardStatus
 * GENERATING. Mirrors PackQuizGenerator, minus the MCQ/CONCEPTUAL split: every card is the same
 * shape, so a batch just asks for its share of the target card count.
 *
 * 1. read up to maxBatches * chunksPerBatch chunks spread evenly across the whole pack
 *    (PackQuizChunkReader, reused as-is — it isn't quiz-specific);
 * 2. split them into up to maxBatches contiguous groups, one LLM call each, sequentially (the
 *    server key's per-minute rate limit is shared, so parallel calls would only trip it sooner);
 * 3. validate every card (PackFlashcardValidator), keep at most the target count;
 * 4. swap the deck in atomically (PackFlashcardBankWriter) — or, on any failure, leave the old deck
 *    as it was and set FAILED with a safe message (never exception text).
 *
 * Every call's real token usage is charged to the owner's monthly budget as it completes
 * (MeteredLlmCall), including calls whose output is later dropped or whose job ultimately fails.
 */
@Component
@RequiredArgsConstructor
public class PackFlashcardGenerator {

    private static final Logger log = LoggerFactory.getLogger(PackFlashcardGenerator.class);

    static final String NO_TEXT = "This document has no text to write flashcards from.";
    static final String TOO_FEW = "Couldn't write enough good flashcards from this document. Please try again.";
    static final String RATE_LIMITED = "The AI provider is busy right now (rate limited). Please try again in a few minutes.";
    static final String FAILED = "Something went wrong while writing the flashcards. Please try again.";

    private final StudyPackRepository studyPackRepository;
    private final PackQuizChunkReader chunkReader;
    private final PackFlashcardPromptBuilder promptBuilder;
    private final ServerChatClientProvider serverChatClientProvider;
    private final MeteredLlmCall meteredLlmCall;
    private final PackFlashcardBankWriter bankWriter;
    private final FlashcardProperties properties;

    /** One LLM call: its sources and how many cards to ask for. */
    record Batch(List<SourceChunk> sources, int cardCount) {
    }

    /** Never throws: whatever happens, the pack leaves GENERATING. */
    public void generate(long packId, User owner) {
        try {
            Optional<String> failure = run(packId, owner);
            failure.ifPresent(message -> fail(packId, message));
        } catch (RuntimeException e) {
            log.error("Flashcard deck generation failed for pack {}", packId, e);
            fail(packId, FAILED);
        } catch (Error e) {
            fail(packId, FAILED); // don't leave it GENERATING until the next restart
            throw e;
        }
    }

    /** @return the failure message, or empty once the deck is in place (or the pack went away) */
    private Optional<String> run(long packId, User owner) {
        StudyPack pack = studyPackRepository.findById(packId).orElse(null);
        if (pack == null || pack.getStatus() != StudyPackStatus.READY) {
            log.info("Pack {} was deleted or is no longer READY; dropping its flashcard generation", packId);
            return pack == null ? Optional.empty() : Optional.of(FAILED);
        }
        int chunkCount = pack.getChunkCount() == null ? 0 : pack.getChunkCount();
        List<SourceChunk> chunks = chunkReader.readSpread(packId, owner.getId(), chunkCount, properties.maxChunks());
        if (chunks.isEmpty()) {
            return Optional.of(NO_TEXT);
        }

        ChatClient client = serverChatClientProvider.requireStructured();
        PackFlashcardValidator validator = new PackFlashcardValidator(packId);
        List<PackFlashcard> cards = new ArrayList<>();
        boolean rateLimited = false;
        int answeredCalls = 0;
        for (Batch batch : planBatches(chunks)) {
            try {
                GeneratedFlashcardBatch output = callWithRetry(client, owner,
                        promptBuilder.generationUser(batch.sources(), batch.cardCount()));
                answeredCalls++;
                cards.addAll(validator.validate(output, batch.sources()));
            } catch (RuntimeException e) {
                rateLimited |= isRateLimited(e);
                log.warn("Flashcard generation call failed for pack {}: {}", packId, describe(e));
            }
        }

        List<PackFlashcard> deck = select(cards);
        if (deck.size() < properties.minCards()) {
            // "Too few" only when the model did answer; a provider that never did is a failure.
            return Optional.of(rateLimited ? RATE_LIMITED : answeredCalls == 0 ? FAILED : TOO_FEW);
        }
        if (bankWriter.replace(packId, deck)) {
            log.info("Pack {} flashcard deck READY: {} cards ({} generated)", packId, deck.size(), cards.size());
        } else {
            log.info("Pack {} was deleted or its generation reset meanwhile; discarding {} cards", packId, deck.size());
        }
        return Optional.empty();
    }

    /**
     * Contiguous groups of the (document-ordered) chunks, sizes differing by at most one, over at
     * most maxBatches calls. Each call asks for an equal share of the target, capped per call.
     */
    List<Batch> planBatches(List<SourceChunk> chunks) {
        int batches = Math.min(chunks.size(), properties.maxBatches());
        int perBatch = Math.min(properties.maxCardsPerBatch(), ceilDiv(properties.targetCards(), batches));
        List<Batch> plan = new ArrayList<>(batches);
        for (int b = 0; b < batches; b++) {
            int from = b * chunks.size() / batches;
            int to = (b + 1) * chunks.size() / batches;
            plan.add(new Batch(chunks.subList(from, to), perBatch));
        }
        return plan;
    }

    /** At most the target deck size, in order. */
    private List<PackFlashcard> select(List<PackFlashcard> cards) {
        int target = properties.targetCards();
        return cards.size() <= target ? cards : cards.subList(0, target);
    }

    /** The job runs on its own pool, so waiting out a rate limit here blocks no request. */
    private GeneratedFlashcardBatch callWithRetry(ChatClient client, User owner, String userPrompt) {
        return meteredLlmCall.entityWithRetry(client, owner, promptBuilder.generationSystem(), userPrompt,
                GeneratedFlashcardBatch.class, properties.rateLimitRetries(), properties.rateLimitBackoff());
    }

    static boolean isRateLimited(Throwable error) {
        return MeteredLlmCall.isRateLimited(error);
    }

    /** Provider errors carry only the provider's own status/body; anything else (e.g. a JSON parse
     *  error, whose message quotes the model output — i.e. document text) is logged by type only. */
    private static String describe(RuntimeException e) {
        return e instanceof NonTransientAiException || e instanceof TransientAiException
                ? e.toString() : e.getClass().getName();
    }

    private void fail(long packId, String message) {
        try {
            if (studyPackRepository.failFlashcardGeneration(packId, message, LocalDateTime.now()) > 0) {
                log.info("Pack {} flashcard deck FAILED: {}", packId, message);
            }
        } catch (RuntimeException e) {
            log.error("Could not mark the flashcard deck of pack {} FAILED", packId, e);
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
