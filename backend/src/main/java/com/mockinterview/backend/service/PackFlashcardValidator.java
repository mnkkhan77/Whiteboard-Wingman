package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.GeneratedFlashcardBatch;
import com.mockinterview.backend.entity.PackFlashcard;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the LLM's raw output into deck rows, dropping anything that isn't a usable card. The model
 * is never trusted to follow the format: every rule below is enforced here, per card, so one bad
 * card costs only itself. Mirrors PackQuizQuestionValidator.
 *
 * Rules (a card failing any is dropped):
 * - a non-blank front of at most {@value #MAX_FRONT_CHARS} characters;
 * - a non-blank back of at most {@value #MAX_BACK_CHARS} characters;
 * - sourceNumber names one of the batch's sources (may be omitted when the batch had only one);
 * - not a duplicate (same front ignoring case, spacing and punctuation) of a card already accepted
 *   for this deck.
 * Accepted values are trimmed. New cards start due immediately (never reviewed).
 */
public final class PackFlashcardValidator {

    static final int MAX_FRONT_CHARS = 300;
    static final int MAX_BACK_CHARS = 1000;
    private static final int MAX_SECTION_CHARS = 500;

    private final long packId;
    private final Set<String> acceptedFronts = new HashSet<>();

    /** One validator per deck: it remembers accepted fronts to drop duplicates across batches. */
    public PackFlashcardValidator(long packId) {
        this.packId = packId;
    }

    public List<PackFlashcard> validate(GeneratedFlashcardBatch batch, List<SourceChunk> sources) {
        List<PackFlashcard> accepted = new ArrayList<>();
        if (batch == null || batch.cards() == null) {
            return accepted;
        }
        for (GeneratedFlashcardBatch.Item item : batch.cards()) {
            validateOne(item, sources).ifPresent(accepted::add);
        }
        return accepted;
    }

    Optional<PackFlashcard> validateOne(GeneratedFlashcardBatch.Item item, List<SourceChunk> sources) {
        if (item == null) {
            return Optional.empty();
        }
        String front = trimmed(item.front());
        String back = trimmed(item.back());
        SourceChunk source = sourceOf(item.sourceNumber(), sources);
        if (front == null || front.length() > MAX_FRONT_CHARS
                || back == null || back.length() > MAX_BACK_CHARS
                || source == null) {
            return Optional.empty();
        }
        // Last, so a card dropped for another reason doesn't block a later valid duplicate.
        if (!acceptedFronts.add(normalize(front))) {
            return Optional.empty();
        }

        PackFlashcard card = new PackFlashcard();
        card.setPackId(packId);
        card.setFront(front);
        card.setBack(back);
        card.setSourcePage(source.page());
        card.setSourceSection(truncate(trimmed(source.section()), MAX_SECTION_CHARS));
        card.setSourceChunkIndex(source.chunkIndex());
        card.setDueAt(LocalDateTime.now());
        return Optional.of(card);
    }

    private static SourceChunk sourceOf(Integer sourceNumber, List<SourceChunk> sources) {
        if (sourceNumber == null) {
            return sources.size() == 1 ? sources.get(0) : null;
        }
        return sourceNumber >= 1 && sourceNumber <= sources.size() ? sources.get(sourceNumber - 1) : null;
    }

    /** Case, spacing and punctuation don't make two fronts different. */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String trimmed(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
