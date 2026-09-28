package com.mockinterview.backend.service;

import com.mockinterview.backend.config.QuizProperties;
import com.mockinterview.backend.dto.GeneratedQuizBatch;
import com.mockinterview.backend.entity.PackQuizQuestion;
import com.mockinterview.backend.entity.QuestionType;
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
 * The question-bank generation job (docs/study-packs-contract.md "Question bank"), run on
 * QuizGenerationExecutor for a pack PackQuizService has just moved to quizStatus GENERATING:
 *
 * 1. read up to maxBatches * chunksPerBatch chunks spread evenly across the whole pack
 *    (PackQuizChunkReader) — not just the start of the document;
 * 2. split them into up to maxBatches contiguous groups, one LLM call each, sequentially (the
 *    server key's per-minute rate limit is shared, so parallel calls would only trip it sooner),
 *    each asking for its share of the target — half MCQ, half CONCEPTUAL — as structured JSON;
 * 3. validate every question (PackQuizQuestionValidator), keep at most half the target per type;
 * 4. swap the bank in atomically (PackQuizBankWriter) — or, on any failure, leave the old bank as
 *    it was and set FAILED with a safe message (never exception text).
 *
 * Every call's real token usage is charged to the owner's monthly budget as it completes
 * (MeteredLlmCall), including calls whose output is later dropped or whose job ultimately fails.
 * A call that fails is skipped; the job only fails if the rest can't make a usable bank.
 */
@Component
@RequiredArgsConstructor
public class PackQuizGenerator {

    private static final Logger log = LoggerFactory.getLogger(PackQuizGenerator.class);

    static final String NO_TEXT = "This document has no text to write questions from.";
    static final String TOO_FEW = "Couldn't write enough good questions from this document. Please try again.";
    static final String RATE_LIMITED = "The AI provider is busy right now (rate limited). Please try again in a few minutes.";
    static final String FAILED = "Something went wrong while writing the questions. Please try again.";

    private final StudyPackRepository studyPackRepository;
    private final PackQuizChunkReader chunkReader;
    private final PackQuizPromptBuilder promptBuilder;
    private final ServerChatClientProvider serverChatClientProvider;
    private final MeteredLlmCall meteredLlmCall;
    private final PackQuizBankWriter bankWriter;
    private final QuizProperties properties;

    /** One LLM call: its sources and how many questions of each type to ask for. */
    record Batch(List<SourceChunk> sources, int mcq, int conceptual) {
    }

    /** Never throws: whatever happens, the pack leaves GENERATING. */
    public void generate(long packId, User owner) {
        try {
            Optional<String> failure = run(packId, owner);
            failure.ifPresent(message -> fail(packId, message));
        } catch (RuntimeException e) {
            log.error("Question bank generation failed for pack {}", packId, e);
            fail(packId, FAILED);
        } catch (Error e) {
            fail(packId, FAILED); // don't leave it GENERATING until the next restart
            throw e;
        }
    }

    /** @return the failure message, or empty once the bank is in place (or the pack went away) */
    private Optional<String> run(long packId, User owner) {
        StudyPack pack = studyPackRepository.findById(packId).orElse(null);
        if (pack == null || pack.getStatus() != StudyPackStatus.READY) {
            log.info("Pack {} was deleted or is no longer READY; dropping its question generation", packId);
            return pack == null ? Optional.empty() : Optional.of(FAILED);
        }
        int chunkCount = pack.getChunkCount() == null ? 0 : pack.getChunkCount();
        List<SourceChunk> chunks = chunkReader.readSpread(packId, owner.getId(), chunkCount, properties.maxChunks());
        if (chunks.isEmpty()) {
            return Optional.of(NO_TEXT);
        }

        ChatClient client = serverChatClientProvider.requireStructured();
        PackQuizQuestionValidator validator = new PackQuizQuestionValidator(packId);
        List<PackQuizQuestion> questions = new ArrayList<>();
        boolean rateLimited = false;
        int answeredCalls = 0;
        for (Batch batch : planBatches(chunks)) {
            try {
                GeneratedQuizBatch output = callWithRetry(client, owner,
                        promptBuilder.generationUser(batch.sources(), batch.mcq(), batch.conceptual()));
                answeredCalls++;
                questions.addAll(validator.validate(output, batch.sources()));
            } catch (RuntimeException e) {
                rateLimited |= isRateLimited(e);
                log.warn("Question generation call failed for pack {}: {}", packId, describe(e));
            }
        }

        List<PackQuizQuestion> bank = select(questions);
        if (bank.size() < properties.minQuestions()) {
            // "Too few" only when the model did answer; a provider that never did is a failure.
            return Optional.of(rateLimited ? RATE_LIMITED : answeredCalls == 0 ? FAILED : TOO_FEW);
        }
        if (bankWriter.replace(packId, bank)) {
            log.info("Pack {} question bank READY: {} questions ({} generated)", packId, bank.size(), questions.size());
        } else {
            log.info("Pack {} was deleted or its generation reset meanwhile; discarding {} questions", packId, bank.size());
        }
        return Optional.empty();
    }

    /**
     * Contiguous groups of the (document-ordered) chunks, sizes differing by at most one, over at
     * most maxBatches calls. Each call asks for an equal share of the target, capped per call.
     */
    List<Batch> planBatches(List<SourceChunk> chunks) {
        int batches = Math.min(chunks.size(), properties.maxBatches());
        int perBatch = Math.min(properties.maxQuestionsPerBatch(), ceilDiv(properties.targetQuestions(), batches));
        int mcq = perBatch / 2;
        int conceptual = perBatch - mcq;
        List<Batch> plan = new ArrayList<>(batches);
        for (int b = 0; b < batches; b++) {
            int from = b * chunks.size() / batches;
            int to = (b + 1) * chunks.size() / batches;
            plan.add(new Batch(chunks.subList(from, to), mcq, conceptual));
        }
        return plan;
    }

    /** At most half the target per type (the contract's ~half MCQ / ~half CONCEPTUAL), in order. */
    private List<PackQuizQuestion> select(List<PackQuizQuestion> questions) {
        int perType = ceilDiv(properties.targetQuestions(), 2);
        List<PackQuizQuestion> selected = new ArrayList<>();
        int mcq = 0;
        int conceptual = 0;
        for (PackQuizQuestion q : questions) {
            if (q.getQuestionType() == QuestionType.MCQ && mcq < perType) {
                mcq++;
                selected.add(q);
            } else if (q.getQuestionType() == QuestionType.CONCEPTUAL && conceptual < perType) {
                conceptual++;
                selected.add(q);
            }
        }
        return selected;
    }

    /** The job runs on its own pool, so waiting out a rate limit here blocks no request. */
    private GeneratedQuizBatch callWithRetry(ChatClient client, User owner, String userPrompt) {
        return meteredLlmCall.entityWithRetry(client, owner, promptBuilder.generationSystem(), userPrompt,
                GeneratedQuizBatch.class, properties.rateLimitRetries(), properties.rateLimitBackoff());
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
            if (studyPackRepository.failQuizGeneration(packId, message, LocalDateTime.now()) > 0) {
                log.info("Pack {} question bank FAILED: {}", packId, message);
            }
        } catch (RuntimeException e) {
            log.error("Could not mark the question bank of pack {} FAILED", packId, e);
        }
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
