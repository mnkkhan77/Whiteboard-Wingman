package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.StaticQuestionEntry;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.QuestionType;

import java.util.List;

/**
 * A question picked by a {@link QuestionSource}, before InterviewSessionService copies it into a
 * session's Question row. sourceId is what "never ask the same one twice in a session" compares
 * (it becomes Question.sourceChunkId). The last three fields are pack-quiz only (null otherwise).
 */
public record QuestionDraft(
        String sourceId,
        Difficulty difficulty,
        QuestionType questionType,
        String promptText,
        List<String> options,
        Integer correctOptionIndex,
        String explanation,
        String ioFormat,
        List<StaticQuestionEntry.TestCase> testCases,
        String referenceAnswer,
        Integer sourcePage,
        String sourceSection
) {
    public static QuestionDraft of(StaticQuestionEntry entry) {
        return new QuestionDraft(entry.id(), entry.difficulty(), entry.questionType(), entry.promptText(),
                entry.options(), entry.correctOptionIndex(), entry.explanation(), entry.ioFormat(),
                entry.testCases(), null, null, null);
    }
}
