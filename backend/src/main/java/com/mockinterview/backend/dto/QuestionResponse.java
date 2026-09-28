package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Question;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.Topic;

import java.util.List;

public record QuestionResponse(
        Long id,
        int sequenceNumber,
        Topic topic,
        String promptText,
        QuestionType questionType,
        Difficulty difficulty,
        List<String> options,
        String ioFormat,
        List<TestCaseDto> testCases,
        Long packId,
        String packTitle
) {
    public record TestCaseDto(String input, String expectedOutput) {
        public static TestCaseDto from(Question.TestCase tc) {
            return new TestCaseDto(tc.getInput(), tc.getExpectedOutput());
        }
    }

    public static QuestionResponse from(Question q) {
        return from(q, PackRef.NONE);
    }

    /** Never includes the correct option, explanation or reference answer: those stay server-side. */
    public static QuestionResponse from(Question q, PackRef pack) {
        return new QuestionResponse(
                q.getId(), q.getSequenceNumber(), q.getTopic(), q.getPromptText(), q.getQuestionType(), q.getDifficulty(),
                // List.copyOf forces the lazy collection to actually load here, while the caller's
                // transaction is still open — q.getOptions() alone would just hand back an
                // uninitialized Hibernate proxy that blows up later when Jackson serializes it,
                // outside the transaction (this is exactly what testCases' .stream()...toList() below
                // already does implicitly, which is why only options ever surfaced this bug).
                List.copyOf(q.getOptions()),
                q.getIoFormat(),
                q.getTestCases().stream().map(TestCaseDto::from).toList(),
                pack.packId(),
                pack.packTitle()
        );
    }
}
