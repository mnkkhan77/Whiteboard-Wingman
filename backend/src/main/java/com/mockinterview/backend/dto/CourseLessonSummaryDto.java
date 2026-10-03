package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.CourseLesson;

/** One lesson as listed in a course outline (docs/study-packs-contract.md "Course from a pack") —
 *  no content, so the outline stays a light request even for a 20-lesson course. */
public record CourseLessonSummaryDto(Long id, String title, String summary, boolean hasContent, boolean completed) {
    public static CourseLessonSummaryDto from(CourseLesson lesson) {
        return new CourseLessonSummaryDto(lesson.getId(), lesson.getTitle(), lesson.getSummary(),
                lesson.getContent() != null, lesson.isCompleted());
    }
}
