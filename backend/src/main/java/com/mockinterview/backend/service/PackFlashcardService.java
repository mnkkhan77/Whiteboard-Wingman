package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.FlashcardDeckDto;
import com.mockinterview.backend.dto.PackDto;
import com.mockinterview.backend.dto.PackFlashcardDto;
import com.mockinterview.backend.entity.FlashcardStatus;
import com.mockinterview.backend.entity.PackFlashcard;
import com.mockinterview.backend.entity.ReviewQuality;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.PackChatException;
import com.mockinterview.backend.repository.PackFlashcardRepository;
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
import java.util.List;
import java.util.NoSuchElementException;

/**
 * The gatekeeping half of "flashcards from a pack" (docs/study-packs-contract.md "Flashcards from a
 * pack"): who may generate a deck, read it or review a card, and when. The work itself is
 * elsewhere — PackFlashcardGenerator writes the deck, Sm2Scheduler schedules a reviewed card.
 * Mirrors PackQuizService; unlike a quiz, studying a deck never calls the LLM, so only generation
 * checks the monthly token quota.
 *
 * Refusals, all thrown before anything changes: CHAT_UNAVAILABLE 503 (no server key, generation
 * only), not found / not owner 404, PACK_NOT_READY 409, FLASHCARDS_ALREADY_GENERATING /
 * FLASHCARDS_NOT_READY 409, CHAT_QUOTA_EXCEEDED 429 (generation only).
 */
@Service
@RequiredArgsConstructor
public class PackFlashcardService {

    private static final Logger log = LoggerFactory.getLogger(PackFlashcardService.class);

    static final String INTERRUPTED = "Flashcard generation was interrupted (the server restarted). Please try again.";
    static final String BUSY = "The server is busy writing other flashcard decks right now. Please try again in a minute.";

    private final StudyPackRepository studyPackRepository;
    private final PackFlashcardRepository flashcardRepository;
    private final ServerChatClientProvider serverChatClientProvider;
    private final ChatQuotaService chatQuotaService;
    private final PackFlashcardGenerator generator;
    private final FlashcardGenerationExecutor executor;

    /**
     * Starts (re)generating the pack's deck in the background and returns at once with
     * flashcardStatus GENERATING (the 202). Regenerating a READY or FAILED deck is allowed; the old
     * deck stays in use until the new one replaces it.
     */
    public PackDto requestGeneration(User user, Long packId) {
        serverChatClientProvider.requireStructured();
        StudyPack pack = findOwned(user, packId);
        requirePackReady(pack);
        if (pack.getFlashcardStatus() == FlashcardStatus.GENERATING) {
            throw alreadyGenerating(); // cheap early answer; the conditional update below is the real gate
        }
        chatQuotaService.requireAvailable(user);

        LocalDateTime now = LocalDateTime.now();
        if (studyPackRepository.startFlashcardGeneration(packId, now) == 0) {
            throw alreadyGenerating(); // lost the race to a simultaneous request (or deleted meanwhile)
        }
        pack.setFlashcardStatus(FlashcardStatus.GENERATING);
        pack.setFlashcardErrorMessage(null);
        pack.setUpdatedAt(now);
        try {
            executor.submit(() -> generator.generate(packId, user));
        } catch (TaskRejectedException e) {
            log.warn("Flashcard generation queue full; refusing pack {}", packId);
            studyPackRepository.failFlashcardGeneration(packId, BUSY, now);
            pack.setFlashcardStatus(FlashcardStatus.FAILED);
            pack.setFlashcardErrorMessage(BUSY);
        }
        return PackDto.from(pack); // built from the detached row, so a fast job can't make it racy
    }

    /** The whole deck (oldest-created first) plus how many cards are due right now. */
    public FlashcardDeckDto deck(User user, Long packId) {
        StudyPack pack = requireDeckReady(user, packId);
        List<PackFlashcard> cards = flashcardRepository.findByPackIdOrderByIdAsc(pack.getId());
        long dueCount = flashcardRepository.countByPackIdAndDueAtLessThanEqual(pack.getId(), LocalDateTime.now());
        return new FlashcardDeckDto(cards.stream().map(PackFlashcardDto::from).toList(), dueCount);
    }

    /** Applies the SM-2 schedule for one answer and persists it. */
    public PackFlashcardDto review(User user, Long packId, Long cardId, ReviewQuality quality) {
        StudyPack pack = requireDeckReady(user, packId);
        PackFlashcard card = flashcardRepository.findByIdAndPackId(cardId, pack.getId())
                .orElseThrow(() -> new NoSuchElementException("Flashcard not found"));
        Sm2Scheduler.review(card, quality, LocalDateTime.now());
        return PackFlashcardDto.from(flashcardRepository.save(card));
    }

    /**
     * A job lives only on this JVM's executor, so after a restart a GENERATING deck has nobody
     * working on it: mark it FAILED so the user can simply generate again. Assumes a single
     * backend instance, like the rest of the pipeline.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedGenerations() {
        int reset = studyPackRepository.failAllFlashcardGenerations(FlashcardStatus.GENERATING, INTERRUPTED, LocalDateTime.now());
        if (reset > 0) {
            log.info("Marked {} interrupted flashcard deck generation(s) FAILED", reset);
        }
    }

    private StudyPack requireDeckReady(User user, Long packId) {
        StudyPack pack = findOwned(user, packId);
        requirePackReady(pack);
        if (pack.getFlashcardStatus() != FlashcardStatus.READY || pack.getFlashcardCount() <= 0) {
            throw new PackChatException(HttpStatus.CONFLICT, PackChatException.FLASHCARDS_NOT_READY,
                    switch (pack.getFlashcardStatus()) {
                        case GENERATING -> "This pack's flashcards are still being written — try again in a moment.";
                        case FAILED -> "Generating this pack's flashcards failed — generate them again to study.";
                        default -> "Generate this pack's flashcards first.";
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
                    "This study pack is " + pack.getStatus() + " — flashcards are available once it's READY.");
        }
    }

    private static PackChatException alreadyGenerating() {
        return new PackChatException(HttpStatus.CONFLICT, PackChatException.FLASHCARDS_ALREADY_GENERATING,
                "This pack's flashcards are already being written.");
    }
}
