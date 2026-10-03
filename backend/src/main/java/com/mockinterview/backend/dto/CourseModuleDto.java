package com.mockinterview.backend.dto;

import java.util.List;

public record CourseModuleDto(String title, List<CourseLessonSummaryDto> lessons) {
}
