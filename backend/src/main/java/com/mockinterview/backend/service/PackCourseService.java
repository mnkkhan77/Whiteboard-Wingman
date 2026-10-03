package com.mockinterview.backend.service;

import com.mockinterview.backend.config.CourseProperties;
import com.mockinterview.backend.dto.CourseDto;
import com.mockinterview.backend.dto.CourseLessonDto;
import com.mockinterview.backend.dto.CourseLessonSummaryDto;
import com.mockinterview.backend.dto.CourseModuleDto;
import com.mockinterview.backend.dto.PackDto;
import com.mockinterview.backend.entity.CourseLesson;
import com.mockinterview.backend.entity.CourseStatus;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import com.mockinterview.backend.repository.CourseLessonRepository;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The gatekeeping half of "course from a pack" (docs/study-packs-contract.md "Course from a pack"):
 * who may generate an outline, read it, open a lesson or mark one complete, and when. Outline
 * generation itself is elsewhere (PackCourseGenerator); a lesson's content is written right here,
 * lazily, the first time it's opened — a single LLM call, unlike the background job an outline or
 * a quiz bank needs. Mirrors PackQuizService / PackFlashcardService.
 *
 * Refusals, all thrown before anything changes: CHAT_UNAVAILABLE 503 (no server key), not found /
 * not owner 404, PACK_NOT_READY 409, COURSE_ALREADY_GENERATING / COURSE_NOT_READY 409,
 * CHAT_QUOTA_EXCEEDED 429 (generation and opening a lesson for the first time); marking a lesson
 * complete never calls the LLM and never checks the quota.
 */
@Service
@RequiredArgsConstructor
public class PackCourseService {

    private static final Logger log = LoggerFactory.getLogger(PackCourseService.class);

    static final String INTERRUPTED = "Course generation was interrupted (the server restarted). Please try again.";
    static final String BUSY = "The server is busy designing other courses right now. Please try again in a minute.";

    private final StudyPackRepository studyPackRepository;
    private final CourseLessonRepository lessonRepository;
    private final ServerChatClientProvider serverChatClientProvider;
    private final ChatQuotaService chatQuotaService;
    private final PackCourseGenerator generator;
    private final CourseGenerationExecutor executor;
    private final PackQuizChunkReader chunkReader;
    private final PackCoursePromptBuilder promptBuilder;
    private final MeteredLlmCall meteredLlmCall;
    private final CourseProperties properties;

    /**
     * Starts (re)generating the pack's outline in the background and returns at once with
     * courseStatus GENERATING (the 202). Regenerating a READY or FAILED outline is allowed; the old
     * outline (and every lesson's written content, and completion state) is replaced.
     */
    public PackDto requestGeneration(User user, Long packId) {
        serverChatClientProvider.requireStructured();
        StudyPack pack = findOwned(user, packId);
        requirePackReady(pack);
        if (pack.getCourseStatus() == CourseStatus.GENERATING) {
            throw alreadyGenerating(); // cheap early answer; the conditional update below is the real gate
        }
        chatQuotaService.requireAvailable(user);

        LocalDateTime now = LocalDateTime.now();
        if (studyPackRepository.startCourseGeneration(packId, now) == 0) {
            throw alreadyGenerating(); // lost the race to a simultaneous request (or deleted meanwhile)
        }
        pack.setCourseStatus(CourseStatus.GENERATING);
        pack.setCourseErrorMessage(null);
        pack.setUpdatedAt(now);
        try {
            executor.submit(() -> generator.generate(packId, user));
        } catch (TaskRejectedException e) {
            log.warn("Course generation queue full; refusing pack {}", packId);
            studyPackRepository.failCourseGeneration(packId, BUSY, now);
            pack.setCourseStatus(CourseStatus.FAILED);
            pack.setCourseErrorMessage(BUSY);
        }
        return PackDto.from(pack); // built from the detached row, so a fast job can't make it racy
    }

    /** The whole outline grouped into modules, plus how many lessons are completed. */
    public CourseDto course(User user, Long packId) {
        StudyPack pack = requireCourseReady(user, packId);
        List<CourseLesson> lessons = lessonRepository.findByPackIdOrderByModuleIndexAscLessonIndexInModuleAsc(pack.getId());

        Map<String, List<CourseLesson>> byModule = new LinkedHashMap<>();
        for (CourseLesson lesson : lessons) {
            byModule.computeIfAbsent(lesson.getModuleTitle(), t -> new ArrayList<>()).add(lesson);
        }
        List<CourseModuleDto> modules = byModule.values().stream()
                .map(group -> new CourseModuleDto(group.get(0).getModuleTitle(),
                        group.stream().map(CourseLessonSummaryDto::from).toList()))
                .toList();
        long completed = lessons.stream().filter(CourseLesson::isCompleted).count();
        return new CourseDto(modules, lessons.size(), (int) completed);
    }

    /** Returns the lesson, generating and caching its content first if this is the first time it's opened. */
    public CourseLessonDto lesson(User user, Long packId, Long lessonId) {
        StudyPack pack = requireCourseReady(user, packId);
        CourseLesson lesson = findLesson(pack.getId(), lessonId);
        if (lesson.getContent() != null) {
            return CourseLessonDto.from(lesson);
        }
        writeContent(user, lesson);
        return CourseLessonDto.from(lessonRepository.save(lesson));
    }

    /** Toggles a lesson's completion flag. Pure local state — no LLM call, no quota check. */
    public CourseLessonDto setCompleted(User user, Long packId, Long lessonId, boolean completed) {
        StudyPack pack = requireCourseReady(user, packId);
        CourseLesson lesson = findLesson(pack.getId(), lessonId);
        lesson.setCompleted(completed);
        return CourseLessonDto.from(lessonRepository.save(lesson));
    }

    /**
     * A job lives only on this JVM's executor, so after a restart a GENERATING outline has nobody
     * working on it: mark it FAILED so the user can simply generate again. Assumes a single
     * backend instance, like the rest of the pipeline.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedGenerations() {
        int reset = studyPackRepository.failAllCourseGenerations(CourseStatus.GENERATING, INTERRUPTED, LocalDateTime.now());
        if (reset > 0) {
            log.info("Marked {} interrupted course generation(s) FAILED", reset);
        }
    }

    /**
     * One LLM call charged to the owner's quota, from the lesson's own assigned chunk range.
     * @throws PackChatException 429 CHAT_QUOTA_EXCEEDED, or 503/502 LLM_RATE_LIMITED/LLM_ERROR if
     *         the call fails — nothing is saved then, same as a pack quiz session's grading failure.
     */
    private void writeContent(User user, CourseLesson lesson) {
        ChatClient client = serverChatClientProvider.requireStructured();
        chatQuotaService.requireAvailable(user);
        List<SourceChunk> chunks = chunkReader.readRange(lesson.getPackId(), user.getId(),
                lesson.getSourceChunkStart(), lesson.getSourceChunkEnd());
        if (chunks.isEmpty()) {
            throw new PackChatException(HttpStatus.BAD_GATEWAY, PackChatException.LLM_ERROR,
                    "Could not find this lesson's source text. Please try regenerating the course.");
        }
        String userPrompt = promptBuilder.lessonUser(lesson.getTitle(), lesson.getSummary(), chunks);
        try {
            String content = meteredLlmCall.textWithRetry(client, user, promptBuilder.lessonSystem(), userPrompt,
                    properties.lessonRateLimitRetries(), properties.lessonRateLimitBackoff());
            lesson.setContent(content);
        } catch (RuntimeException e) {
            boolean rateLimited = MeteredLlmCall.isRateLimited(e);
            log.warn("Course lesson {} content generation failed{}: {}", lesson.getId(),
                    rateLimited ? " (rate limited)" : "", e.toString());
            throw rateLimited
                    ? new PackChatException(HttpStatus.SERVICE_UNAVAILABLE, PackChatException.LLM_RATE_LIMITED,
                            "The AI provider is busy right now (rate limited). Please try again in a moment.")
                    : new PackChatException(HttpStatus.BAD_GATEWAY, PackChatException.LLM_ERROR,
                            "This lesson's content couldn't be written. Please try again.");
        }
        SourceChunk first = chunks.get(0);
        lesson.setSourcePage(first.page());
        lesson.setSourceSection(first.section());
    }

    private CourseLesson findLesson(Long packId, Long lessonId) {
        return lessonRepository.findByIdAndPackId(lessonId, packId)
                .orElseThrow(() -> new NoSuchElementException("Lesson not found"));
    }

    private StudyPack requireCourseReady(User user, Long packId) {
        StudyPack pack = findOwned(user, packId);
        requirePackReady(pack);
        if (pack.getCourseStatus() != CourseStatus.READY || pack.getCourseLessonCount() <= 0) {
            throw new PackChatException(HttpStatus.CONFLICT, PackChatException.COURSE_NOT_READY,
                    switch (pack.getCourseStatus()) {
                        case GENERATING -> "This pack's course is still being designed — try again in a moment.";
                        case FAILED -> "Designing this pack's course failed — generate it again.";
                        default -> "Generate this pack's course first.";
                    });
        }
        return pack;
    }

    private StudyPack findOwned(User user, Long packId) {
        // Same 404 for "doesn't exist" and "not yours", as in StudyPackService.
        return studyPackRepository.findByIdAndOwner(packId, user)
                .orElseThrow(() -> new NoSuchElementException("Study pack not found"));
    }

    private static void requirePackReady(StudyPack pack) {
        if (pack.getStatus() != StudyPackStatus.READY) {
            throw new PackChatException(HttpStatus.CONFLICT, PackChatException.PACK_NOT_READY,
                    "This study pack is " + pack.getStatus() + " — a course is available once it's READY.");
        }
    }

    private static PackChatException alreadyGenerating() {
        return new PackChatException(HttpStatus.CONFLICT, PackChatException.COURSE_ALREADY_GENERATING,
                "This pack's course is already being designed.");
    }
}
