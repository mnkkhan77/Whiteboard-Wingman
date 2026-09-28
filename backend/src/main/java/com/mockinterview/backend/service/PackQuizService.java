package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.PackDto;
import com.mockinterview.backend.entity.QuizStatus;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import com.mockinterview.backend.repository.StudyPackRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.NoSuchElementException;

/**
 * The gatekeeping half of "quiz from a pack" (docs/study-packs-contract.md "Quiz from a pack"):
 * who may generate a question bank or start a quiz, and when. The work itself is elsewhere —
 * PackQuizGenerator writes the bank, InterviewSessionService runs the quiz.
 *
 * Refusals, all thrown before anything changes: CHAT_UNAVAILABLE 503 (no server key), not found /
 * not owner 404, PACK_NOT_READY 409, QUIZ_ALREADY_GENERATING / QUIZ_NOT_READY 409,
 * CHAT_QUOTA_EXCEEDED 429. The quota is only checked here — at generate and at quiz start — never
 * mid-session: a started quiz always finishes (its tokens are still charged).
 */
@Service
@RequiredArgsConstructor
public class PackQuizService {

    private static final Logger log = LoggerFactory.getLogger(PackQuizService.class);

    static final String INTERRUPTED = "Question generation was interrupted (the server restarted). Please try again.";
    static final String BUSY = "The server is busy writing other quizzes right now. Please try again in a minute.";

    private final StudyPackRepository studyPackRepository;
    private final ServerChatClientProvider serverChatClientProvider;
    private final ChatQuotaService chatQuotaService;
    private final PackQuizGenerator generator;
    private final QuizGenerationExecutor executor;

    /**
     * Starts (re)generating the pack's bank in the background and returns at once with
     * quizStatus GENERATING (the 202). Regenerating a READY or FAILED bank is allowed; the old
     * bank stays in use until the new one replaces it.
     */
    public PackDto requestGeneration(User user, Long packId) {
        serverChatClientProvider.requireStructured();
        StudyPack pack = findOwned(user, packId);
        requirePackReady(pack);
        if (pack.getQuizStatus() == QuizStatus.GENERATING) {
            throw alreadyGenerating(); // cheap early answer; the conditional update below is the real gate
        }
        chatQuotaService.requireAvailable(user);

        LocalDateTime now = LocalDateTime.now();
        if (studyPackRepository.startQuizGeneration(packId, now) == 0) {
            throw alreadyGenerating(); // lost the race to a simultaneous request (or deleted meanwhile)
        }
        pack.setQuizStatus(QuizStatus.GENERATING);
        pack.setQuizErrorMessage(null);
        pack.setUpdatedAt(now);
        try {
            executor.submit(() -> generator.generate(packId, user));
        } catch (TaskRejectedException e) {
            log.warn("Question generation queue full; refusing pack {}", packId);
            studyPackRepository.failQuizGeneration(packId, BUSY, now);
            pack.setQuizStatus(QuizStatus.FAILED);
            pack.setQuizErrorMessage(BUSY);
        }
        return PackDto.from(pack); // built from the detached row, so a fast job can't make it racy
    }

    /** Everything a quiz start needs, checked in one place; returns the (owned, ready) pack. */
    public StudyPack requireQuizStartable(User user, Long packId) {
        serverChatClientProvider.requireStructured();
        StudyPack pack = findOwned(user, packId);
        requirePackReady(pack);
        if (pack.getQuizStatus() != QuizStatus.READY || pack.getQuizQuestionCount() <= 0) {
            throw new PackChatException(HttpStatus.CONFLICT, PackChatException.QUIZ_NOT_READY,
                    switch (pack.getQuizStatus()) {
                        case GENERATING -> "This pack's questions are still being written — try again in a moment.";
                        case FAILED -> "Generating this pack's questions failed — generate them again to start a quiz.";
                        default -> "Generate this pack's questions first to start a quiz.";
                    });
        }
        chatQuotaService.requireAvailable(user);
        return pack;
    }

    /**
     * A job lives only on this JVM's executor, so after a restart a GENERATING bank has nobody
     * working on it: mark it FAILED so the user can simply generate again. Assumes a single
     * backend instance, like the rest of the pipeline.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedGenerations() {
        int reset = studyPackRepository.failAllQuizGenerations(QuizStatus.GENERATING, INTERRUPTED, LocalDateTime.now());
        if (reset > 0) {
            log.info("Marked {} interrupted question bank generation(s) FAILED", reset);
        }
    }

    private StudyPack findOwned(User user, Long packId) {
        // Same 404 for "doesn't exist" and "not yours", as in StudyPackService.
        return studyPackRepository.findByIdAndOwner(packId, user)
                .orElseThrow(() -> new NoSuchElementException("Study pack not found"));
    }

    private static void requirePackReady(StudyPack pack) {
        if (pack.getStatus() != StudyPackStatus.READY) {
            throw new PackChatException(HttpStatus.CONFLICT, PackChatException.PACK_NOT_READY,
                    "This study pack is " + pack.getStatus() + " — quizzes are available once it's READY.");
        }
    }

    private static PackChatException alreadyGenerating() {
        return new PackChatException(HttpStatus.CONFLICT, PackChatException.QUIZ_ALREADY_GENERATING,
                "This pack's questions are already being written.");
    }
}
