package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.PackFlashcard;
import com.mockinterview.backend.repository.PackFlashcardRepository;
import com.mockinterview.backend.repository.StudyPackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Swaps a pack's flashcard deck in one transaction, so readers see either the whole old deck or the
 * whole new one — never a half-written or empty deck — and flashcardStatus flips to READY together
 * with the rows. A separate bean so the @Transactional proxy applies when PackFlashcardGenerator
 * calls it. Mirrors PackQuizBankWriter.
 */
@Component
@RequiredArgsConstructor
public class PackFlashcardBankWriter {

    private final StudyPackRepository studyPackRepository;
    private final PackFlashcardRepository deckRepository;

    /**
     * @return false (and nothing written) when the pack is no longer GENERATING — deleted, or its
     *         job was already failed (e.g. by the startup reset). The status update runs first on
     *         purpose: it row-locks the pack, so a concurrent pack DELETE waits for this commit
     *         (and then cascades the new rows) instead of racing the inserts into an FK violation.
     */
    @Transactional
    public boolean replace(long packId, List<PackFlashcard> cards) {
        if (studyPackRepository.finishFlashcardGeneration(packId, cards.size(), LocalDateTime.now()) == 0) {
            return false;
        }
        deckRepository.deleteByPackId(packId);
        deckRepository.saveAll(cards);
        return true;
    }
}
