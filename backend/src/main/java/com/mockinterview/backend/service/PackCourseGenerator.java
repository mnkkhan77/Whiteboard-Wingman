package com.mockinterview.backend.service;

import com.mockinterview.backend.config.CourseProperties;
import com.mockinterview.backend.dto.GeneratedCourseOutline;
import com.mockinterview.backend.entity.CourseLesson;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The course-outline generation job (docs/study-packs-contract.md "Course from a pack"), run on
 * CourseGenerationExecutor for a pack PackCourseService has just moved to courseStatus GENERATING.
 * Unlike a quiz bank or flashcard deck (many items, several sequential LLM calls), the outline is
 * just titles and summaries, so one call — sampling chunks spread across the whole pack — is
 * enough:
 *
 * 1. read up to outlineSampleChunks chunks spread evenly across the whole pack;
 * 2. one structured LLM call for the whole outline;
 * 3. validate every module/lesson (PackCourseOutlineValidator);
 * 4. trim to at most one lesson per pack chunk (every lesson needs its own non-empty slice to
 *    write its content from later) and assign each surviving lesson a contiguous chunk range;
 * 5. swap the outline in atomically (PackCourseBankWriter) — or, on failure, leave the old outline
 *    as it was and set FAILED with a safe message (never exception text).
 *
 * A lesson's own content is not written here — PackCourseService writes it lazily, the first time
 * the lesson is opened, from its assigned range (read then via PackQuizChunkReader.readRange).
 */
@Component
@RequiredArgsConstructor
public class PackCourseGenerator {

    private static final Logger log = LoggerFactory.getLogger(PackCourseGenerator.class);

    static final String NO_TEXT = "This document has no text to design a course from.";
    static final String TOO_FEW = "Couldn't design enough good lessons from this document. Please try again.";
    static final String RATE_LIMITED = "The AI provider is busy right now (rate limited). Please try again in a few minutes.";
    static final String FAILED = "Something went wrong while designing the course. Please try again.";

    private final StudyPackRepository studyPackRepository;
    private final PackQuizChunkReader chunkReader;
    private final PackCoursePromptBuilder promptBuilder;
    private final ServerChatClientProvider serverChatClientProvider;
    private final MeteredLlmCall meteredLlmCall;
    private final PackCourseBankWriter bankWriter;
    private final CourseProperties properties;

    /** Never throws: whatever happens, the pack leaves GENERATING. */
    public void generate(long packId, User owner) {
        try {
            Optional<String> failure = run(packId, owner);
            failure.ifPresent(message -> fail(packId, message));
        } catch (RuntimeException e) {
            log.error("Course outline generation failed for pack {}", packId, e);
            fail(packId, FAILED);
        } catch (Error e) {
            fail(packId, FAILED); // don't leave it GENERATING until the next restart
            throw e;
        }
    }

    /** @return the failure message, or empty once the outline is in place (or the pack went away) */
    private Optional<String> run(long packId, User owner) {
        StudyPack pack = studyPackRepository.findById(packId).orElse(null);
        if (pack == null || pack.getStatus() != StudyPackStatus.READY) {
            log.info("Pack {} was deleted or is no longer READY; dropping its course generation", packId);
            return pack == null ? Optional.empty() : Optional.of(FAILED);
        }
        int chunkCount = pack.getChunkCount() == null ? 0 : pack.getChunkCount();
        List<SourceChunk> sample = chunkReader.readSpread(packId, owner.getId(), chunkCount, properties.outlineSampleChunks());
        if (sample.isEmpty()) {
            return Optional.of(NO_TEXT);
        }

        GeneratedCourseOutline outline;
        try {
            ChatClient client = serverChatClientProvider.requireStructured();
            outline = meteredLlmCall.entityWithRetry(client, owner, promptBuilder.outlineSystem(),
                    promptBuilder.outlineUser(sample, properties.targetModules(), properties.lessonsPerModule()),
                    GeneratedCourseOutline.class, properties.rateLimitRetries(), properties.rateLimitBackoff());
        } catch (RuntimeException e) {
            log.warn("Course outline call failed for pack {}: {}", packId, describe(e));
            return Optional.of(PackCourseGenerator.isRateLimited(e) ? RATE_LIMITED : FAILED);
        }

        PackCourseOutlineValidator validator = new PackCourseOutlineValidator(packId,
                properties.targetModules(), properties.lessonsPerModule());
        List<CourseLesson> lessons = assignChunkRanges(validator.validate(outline), chunkCount);
        if (lessons.size() < properties.minLessons()) {
            return Optional.of(TOO_FEW);
        }
        if (bankWriter.replace(packId, lessons)) {
            log.info("Pack {} course outline READY: {} lessons", packId, lessons.size());
        } else {
            log.info("Pack {} was deleted or its generation reset meanwhile; discarding {} lessons", packId, lessons.size());
        }
        return Optional.empty();
    }

    /**
     * Keeps at most one lesson per pack chunk (every lesson needs its own non-empty source slice)
     * and gives each surviving lesson a contiguous, evenly sized range of the pack's chunks, in
     * order — the same from/to split PackQuizGenerator uses for its batches.
     */
    static List<CourseLesson> assignChunkRanges(List<CourseLesson> validated, int chunkCount) {
        int total = Math.min(validated.size(), Math.max(chunkCount, 0));
        List<CourseLesson> kept = validated.subList(0, total);
        for (int i = 0; i < total; i++) {
            CourseLesson lesson = kept.get(i);
            lesson.setSourceChunkStart(i * chunkCount / total);
            lesson.setSourceChunkEnd((i + 1) * chunkCount / total);
        }
        return kept;
    }

    static boolean isRateLimited(Throwable error) {
        return MeteredLlmCall.isRateLimited(error);
    }

    /** Provider errors carry only the provider's own status/body; anything else (e.g. a JSON parse
     *  error, whose message quotes the model output — i.e. document text) is logged by type only. */
    private static String describe(RuntimeException e) {
        return MeteredLlmCall.isRateLimited(e) ? "rate limited" : e.getClass().getName();
    }

    private void fail(long packId, String message) {
        try {
            if (studyPackRepository.failCourseGeneration(packId, message, LocalDateTime.now()) > 0) {
                log.info("Pack {} course outline FAILED: {}", packId, message);
            }
        } catch (RuntimeException e) {
            log.error("Could not mark the course outline of pack {} FAILED", packId, e);
        }
    }
}
