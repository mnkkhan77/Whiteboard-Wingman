package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.CourseLesson;

/** GET /api/packs/{id}/course/lessons/{lessonId}: full lesson detail, generating its content the
 *  first time it's opened (docs/study-packs-contract.md "Course from a pack"). */
public record CourseLessonDto(
        Long id,
        String title,
        String summary,
        String content,
        Integer sourcePage,
        String sourceSection,
        boolean completed
) {
    public static CourseLessonDto from(CourseLesson lesson) {
        return new CourseLessonDto(lesson.getId(), lesson.getTitle(), lesson.getSummary(), lesson.getContent(),
                lesson.getSourcePage(), lesson.getSourceSection(), lesson.isCompleted());
    }
}
