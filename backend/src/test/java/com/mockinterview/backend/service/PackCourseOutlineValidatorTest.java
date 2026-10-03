package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.GeneratedCourseOutline;
import com.mockinterview.backend.dto.GeneratedCourseOutline.Lesson;
import com.mockinterview.backend.dto.GeneratedCourseOutline.Module;
import com.mockinterview.backend.entity.CourseLesson;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PackCourseOutlineValidatorTest {

    private final PackCourseOutlineValidator validator = new PackCourseOutlineValidator(42L, 2, 2);

    private static Lesson lesson(String title, String summary) {
        return new Lesson(title, summary);
    }

    @Test
    void validOutlineIsAcceptedTrimmedAndIndexed() {
        GeneratedCourseOutline outline = new GeneratedCourseOutline(List.of(
                new Module("  Transactions  ", List.of(
                        lesson(" What is a transaction? ", " Defines atomic units of work. "),
                        lesson("ACID properties", "Explains the four guarantees."))),
                new Module("Concurrency", List.of(
                        lesson("Locking", "How locks serialize access.")))));

        List<CourseLesson> accepted = validator.validate(outline);

        assertThat(accepted).hasSize(3);
        CourseLesson first = accepted.get(0);
        assertThat(first.getPackId()).isEqualTo(42L);
        assertThat(first.getModuleIndex()).isZero();
        assertThat(first.getModuleTitle()).isEqualTo("Transactions");
        assertThat(first.getLessonIndexInModule()).isZero();
        assertThat(first.getTitle()).isEqualTo("What is a transaction?");
        assertThat(first.getSummary()).isEqualTo("Defines atomic units of work.");

        CourseLesson second = accepted.get(1);
        assertThat(second.getModuleIndex()).isZero();
        assertThat(second.getLessonIndexInModule()).isEqualTo(1);

        CourseLesson third = accepted.get(2);
        assertThat(third.getModuleIndex()).isEqualTo(1);
        assertThat(third.getModuleTitle()).isEqualTo("Concurrency");
        assertThat(third.getLessonIndexInModule()).isZero();
    }

    @Test
    void moduleCountAndLessonsPerModuleAreCapped() {
        GeneratedCourseOutline outline = new GeneratedCourseOutline(List.of(
                new Module("A", List.of(lesson("A1", "s"), lesson("A2", "s"), lesson("A3", "s"))),
                new Module("B", List.of(lesson("B1", "s"))),
                new Module("C", List.of(lesson("C1", "s")))));

        List<CourseLesson> accepted = validator.validate(outline);

        // maxLessonsPerModule=2 keeps only A1/A2 from module A; maxModules=2 drops module C.
        assertThat(accepted).extracting(CourseLesson::getTitle).containsExactly("A1", "A2", "B1");
    }

    @Test
    void emptyOrInvalidModulesAndLessonsAreDropped() {
        GeneratedCourseOutline outline = new GeneratedCourseOutline(List.of(
                new Module(null, List.of(lesson("x", "y"))),
                new Module("  ", List.of(lesson("x", "y"))),
                new Module("Empty", List.of()),
                new Module("Empty2", null),
                new Module("Bad lessons", Arrays.asList(
                        null,
                        lesson(null, "y"),
                        lesson("  ", "y"),
                        lesson("x", null),
                        lesson("x", "  "),
                        lesson("x".repeat(PackCourseOutlineValidator.MAX_LESSON_TITLE_CHARS + 1), "y"),
                        lesson("x", "y".repeat(PackCourseOutlineValidator.MAX_SUMMARY_CHARS + 1)))),
                new Module("x".repeat(PackCourseOutlineValidator.MAX_MODULE_TITLE_CHARS + 1), List.of(lesson("x", "y")))));

        assertThat(validator.validate(outline)).isEmpty();
        assertThat(validator.validate(null)).isEmpty();
        assertThat(validator.validate(new GeneratedCourseOutline(null))).isEmpty();
    }

    @Test
    void duplicateLessonTitlesAreDroppedAcrossModulesIgnoringCaseSpacingAndPunctuation() {
        GeneratedCourseOutline outline = new GeneratedCourseOutline(List.of(
                new Module("One", List.of(lesson("What is a deadlock?", "s"))),
                new Module("Two", List.of(lesson("what is a   DEADLOCK", "s"), lesson("Detecting cycles", "s")))));

        List<CourseLesson> accepted = validator.validate(outline);

        assertThat(accepted).extracting(CourseLesson::getTitle).containsExactly("What is a deadlock?", "Detecting cycles");
    }
}
