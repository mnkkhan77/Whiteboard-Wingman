package com.mockinterview.backend.dto;

import java.util.List;

/** GET /api/packs/{id}/course response: the whole outline grouped into modules, plus progress. */
public record CourseDto(List<CourseModuleDto> modules, int totalLessons, int completedLessons) {
}
