package com.mockinterview.backend.dto;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.util.List;

/**
 * Structured-output contract for the course-outline LLM call (PackCourseGenerator). Titles and
 * summaries only — a lesson's full content is written later, lazily, one lesson at a time.
 */
public record GeneratedCourseOutline(List<Module> modules) {

    public record Module(
            @JsonPropertyDescription("The module's title") String title,
            List<Lesson> lessons
    ) {
    }

    public record Lesson(
            @JsonPropertyDescription("The lesson's title") String title,
            @JsonPropertyDescription("A one-sentence summary of what this lesson covers") String summary
    ) {
    }
}
