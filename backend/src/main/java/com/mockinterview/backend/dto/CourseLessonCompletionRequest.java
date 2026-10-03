package com.mockinterview.backend.dto;

import jakarta.validation.constraints.NotNull;

/** PUT /api/packs/{id}/course/lessons/{lessonId}/complete body. */
public record CourseLessonCompletionRequest(@NotNull Boolean completed) {
}
