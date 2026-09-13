package com.mockinterview.backend.dto;

/**
 * answerText is intentionally not @NotBlank — a live-coding-round question is answered primarily
 * (or entirely) via code, not a written/spoken explanation, and an MCQ question is answered via
 * selectedOptionIndex alone. InterviewSessionService enforces the real constraint per question type.
 *
 * language is only required for a coding answer in a session with no LLM key (InterviewSessionService
 * grades it by actually running the code against the question's test cases, which needs to know
 * which language to run it as) — otherwise unused.
 */
public record SubmitAnswerRequest(
        String answerText,
        String code,
        String language,
        Integer selectedOptionIndex
) {
}
