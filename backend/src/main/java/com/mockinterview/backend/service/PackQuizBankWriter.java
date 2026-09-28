package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.PackQuizQuestion;
import com.mockinterview.backend.repository.PackQuizQuestionRepository;
import com.mockinterview.backend.repository.StudyPackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Swaps a pack's question bank in one transaction, so readers see either the whole old bank or the
 * whole new one — never a half-written or empty bank — and quizStatus flips to READY together with
 * the rows. A separate bean so the @Transactional proxy applies when PackQuizGenerator calls it.
 */
@Component
@RequiredArgsConstructor
public class PackQuizBankWriter {

    private final StudyPackRepository studyPackRepository;
    private final PackQuizQuestionRepository bankRepository;

    /**
     * @return false (and nothing written) when the pack is no longer GENERATING — deleted, or its
     *         job was already failed (e.g. by the startup reset). The status update runs first on
     *         purpose: it row-locks the pack, so a concurrent pack DELETE waits for this commit
     *         (and then cascades the new rows) instead of racing the inserts into an FK violation.
     */
    @Transactional
    public boolean replace(long packId, List<PackQuizQuestion> questions) {
        if (studyPackRepository.finishQuizGeneration(packId, questions.size(), LocalDateTime.now()) == 0) {
            return false;
        }
        bankRepository.deleteByPackId(packId);
        bankRepository.saveAll(questions);
        return true;
    }
}
