package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.GeneratedCourseOutline;
import com.mockinterview.backend.entity.CourseLesson;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Turns the LLM's raw outline into ordered lesson rows (moduleIndex/lessonIndexInModule assigned
 * here, in output order), dropping anything that isn't usable. Mirrors PackQuizQuestionValidator /
 * PackFlashcardValidator: the model is never trusted to follow the format or the requested size.
 *
 * Rules:
 * - a module needs a non-blank title (at most {@value #MAX_MODULE_TITLE_CHARS} characters) and at
 *   least one valid lesson, else it is dropped entirely;
 * - at most {@code maxModules} modules are kept, in order;
 * - a lesson needs a non-blank title (at most {@value #MAX_LESSON_TITLE_CHARS} characters) and a
 *   non-blank summary (at most {@value #MAX_SUMMARY_CHARS} characters);
 * - at most {@code maxLessonsPerModule} lessons are kept per module, in order;
 * - a lesson title that duplicates one already accepted (ignoring case, spacing and punctuation),
 *   anywhere in the outline, is dropped.
 * Chunk ranges are not assigned here — PackCourseGenerator does that once the final, possibly
 * trimmed, lesson count is known.
 */
public final class PackCourseOutlineValidator {

    static final int MAX_MODULE_TITLE_CHARS = 150;
    static final int MAX_LESSON_TITLE_CHARS = 200;
    static final int MAX_SUMMARY_CHARS = 400;

    private final long packId;
    private final int maxModules;
    private final int maxLessonsPerModule;
    private final Set<String> acceptedTitles = new HashSet<>();

    public PackCourseOutlineValidator(long packId, int maxModules, int maxLessonsPerModule) {
        this.packId = packId;
        this.maxModules = maxModules;
        this.maxLessonsPerModule = maxLessonsPerModule;
    }

    public List<CourseLesson> validate(GeneratedCourseOutline outline) {
        List<CourseLesson> accepted = new ArrayList<>();
        if (outline == null || outline.modules() == null) {
            return accepted;
        }
        int moduleIndex = 0;
        for (GeneratedCourseOutline.Module module : outline.modules()) {
            if (moduleIndex >= maxModules || module == null) {
                continue;
            }
            String moduleTitle = trimmed(module.title());
            if (moduleTitle == null || moduleTitle.length() > MAX_MODULE_TITLE_CHARS || module.lessons() == null) {
                continue;
            }
            List<CourseLesson> lessons = validateLessons(module.lessons(), moduleIndex, moduleTitle);
            if (!lessons.isEmpty()) {
                accepted.addAll(lessons);
                moduleIndex++;
            }
        }
        return accepted;
    }

    private List<CourseLesson> validateLessons(List<GeneratedCourseOutline.Lesson> raw, int moduleIndex, String moduleTitle) {
        List<CourseLesson> lessons = new ArrayList<>();
        for (GeneratedCourseOutline.Lesson item : raw) {
            if (lessons.size() >= maxLessonsPerModule || item == null) {
                continue;
            }
            String title = trimmed(item.title());
            String summary = trimmed(item.summary());
            if (title == null || title.length() > MAX_LESSON_TITLE_CHARS
                    || summary == null || summary.length() > MAX_SUMMARY_CHARS) {
                continue;
            }
            if (!acceptedTitles.add(normalize(title))) {
                continue; // duplicate of a lesson already accepted, anywhere in the outline
            }

            CourseLesson lesson = new CourseLesson();
            lesson.setPackId(packId);
            lesson.setModuleIndex(moduleIndex);
            lesson.setModuleTitle(moduleTitle);
            lesson.setLessonIndexInModule(lessons.size());
            lesson.setTitle(title);
            lesson.setSummary(summary);
            lessons.add(lesson);
        }
        return lessons;
    }

    /** Case, spacing and punctuation don't make two lesson titles different. */
    static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String trimmed(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }
}
