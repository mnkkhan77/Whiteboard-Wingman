package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.GeneratedQuizBatch;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.PackQuizQuestion;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.service.PackQuizChunkReader.SourceChunk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Turns the LLM's raw output into bank rows, dropping anything that isn't a usable question. The
 * model is never trusted to follow the format: every rule below is enforced here, per question, so
 * one bad question costs only itself.
 *
 * Rules (a question failing any is dropped):
 * - type MCQ or CONCEPTUAL, difficulty EASY / MEDIUM / HARD (case-insensitive);
 * - a non-blank prompt of at most {@value #MAX_PROMPT_CHARS} characters;
 * - sourceNumber names one of the batch's sources (may be omitted when the batch had only one);
 * - MCQ: exactly 4 options, each non-blank, at most {@value #MAX_OPTION_CHARS} characters and
 *   distinct from the others (ignoring case and spacing); correctOptionIndex 0-3; a non-blank
 *   explanation;
 * - CONCEPTUAL: a non-blank reference answer;
 * - not a duplicate (same prompt ignoring case, spacing and punctuation) of a question already
 *   accepted for this bank.
 * Accepted values are trimmed; fields that don't belong to the type are dropped.
 */
public final class PackQuizQuestionValidator {

    static final int MAX_PROMPT_CHARS = 1000;
    static final int MAX_OPTION_CHARS = 500;
    static final int MCQ_OPTIONS = 4;
    private static final int MAX_SECTION_CHARS = 500;

    private final long packId;
    private final Set<String> acceptedPrompts = new HashSet<>();

    /** One validator per bank: it remembers accepted prompts to drop duplicates across batches. */
    public PackQuizQuestionValidator(long packId) {
        this.packId = packId;
    }

    public List<PackQuizQuestion> validate(GeneratedQuizBatch batch, List<SourceChunk> sources) {
        List<PackQuizQuestion> accepted = new ArrayList<>();
        if (batch == null || batch.questions() == null) {
            return accepted;
        }
        for (GeneratedQuizBatch.Item item : batch.questions()) {
            validateOne(item, sources).ifPresent(accepted::add);
        }
        return accepted;
    }

    Optional<PackQuizQuestion> validateOne(GeneratedQuizBatch.Item item, List<SourceChunk> sources) {
        if (item == null) {
            return Optional.empty();
        }
        QuestionType type = parseType(item.type());
        Difficulty difficulty = parseDifficulty(item.difficulty());
        String prompt = trimmed(item.prompt());
        SourceChunk source = sourceOf(item.sourceNumber(), sources);
        if (type == null || difficulty == null || prompt == null || prompt.length() > MAX_PROMPT_CHARS || source == null) {
            return Optional.empty();
        }

        PackQuizQuestion question = new PackQuizQuestion();
        question.setPackId(packId);
        question.setQuestionType(type);
        question.setDifficulty(difficulty);
        question.setPrompt(prompt);
        question.setSourcePage(source.page());
        question.setSourceSection(truncate(trimmed(source.section()), MAX_SECTION_CHARS));
        question.setSourceChunkIndex(source.chunkIndex());

        if (type == QuestionType.MCQ) {
            List<String> options = validOptions(item.options());
            String explanation = trimmed(item.explanation());
            Integer correct = item.correctOptionIndex();
            if (options == null || explanation == null || correct == null || correct < 0 || correct >= MCQ_OPTIONS) {
                return Optional.empty();
            }
            question.setOptions(options);
            question.setCorrectOptionIndex(correct);
            question.setExplanation(explanation);
        } else {
            String reference = trimmed(item.referenceAnswer());
            if (reference == null) {
                return Optional.empty();
            }
            question.setReferenceAnswer(reference);
        }

        // Last, so a question dropped for another reason doesn't block a later valid duplicate.
        if (!acceptedPrompts.add(normalize(prompt))) {
            return Optional.empty();
        }
        return Optional.of(question);
    }

    private static List<String> validOptions(List<String> raw) {
        if (raw == null || raw.size() != MCQ_OPTIONS) {
            return null;
        }
        List<String> options = new ArrayList<>(MCQ_OPTIONS);
        Set<String> distinct = new HashSet<>();
        for (String option : raw) {
            String value = trimmed(option);
            if (value == null || value.length() > MAX_OPTION_CHARS || !distinct.add(normalize(value))) {
                return null;
            }
            options.add(value);
        }
        return options;
    }

    private static SourceChunk sourceOf(Integer sourceNumber, List<SourceChunk> sources) {
        if (sourceNumber == null) {
            return sources.size() == 1 ? sources.get(0) : null;
        }
        return sourceNumber >= 1 && sourceNumber <= sources.size() ? sources.get(sourceNumber - 1) : null;
    }

    private static QuestionType parseType(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "MCQ" -> QuestionType.MCQ;
            case "CONCEPTUAL" -> QuestionType.CONCEPTUAL;
            default -> null; // CODING or anything else: not a pack question type
        };
    }

    private static Difficulty parseDifficulty(String raw) {
        try {
            return raw == null ? null : Difficulty.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Case, spacing and punctuation don't make two prompts (or two options) different. */
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
