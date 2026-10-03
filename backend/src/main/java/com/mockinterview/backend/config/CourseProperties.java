package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.course.* — course-outline generation and lazy lesson-content generation for "course from a
 * pack" (docs/study-packs-contract.md "Course from a pack"). See PackCourseGenerator / PackCourseService.
 *
 * @param targetModules          modules to aim for (contract: 5)
 * @param lessonsPerModule       lessons to aim for per module (contract: 4, so ~20 lessons total)
 * @param minLessons             fewer accepted lessons than this (after trimming to the pack's
 *                               chunk count — every lesson needs a non-empty source slice) and the
 *                               outline is FAILED
 * @param outlineSampleChunks    chunks spread across the whole pack given to the one outline call,
 *                               as its only grounding (the full outline is titles/summaries, not
 *                               full content, so this doesn't need PackQuizGenerator's multi-batch
 *                               split)
 * @param rateLimitRetries       retries of the outline call answered with HTTP 429
 * @param rateLimitBackoff       wait before the first retry; doubled for each further one
 * @param lessonRateLimitRetries retries of a 429 while writing one lesson's content — short, since
 *                               a user request is waiting on it
 * @param lessonRateLimitBackoff wait before that retry
 * @param generationThreads      concurrent outline-generation jobs
 * @param queueCapacity          jobs waiting for a thread; beyond it a request fails fast as busy
 */
@ConfigurationProperties(prefix = "app.course")
public record CourseProperties(
        Integer targetModules,
        Integer lessonsPerModule,
        Integer minLessons,
        Integer outlineSampleChunks,
        Integer rateLimitRetries,
        Duration rateLimitBackoff,
        Integer lessonRateLimitRetries,
        Duration lessonRateLimitBackoff,
        Integer generationThreads,
        Integer queueCapacity
) {
    public CourseProperties {
        targetModules = targetModules == null ? 5 : targetModules;
        lessonsPerModule = lessonsPerModule == null ? 4 : lessonsPerModule;
        minLessons = minLessons == null ? 3 : minLessons;
        outlineSampleChunks = outlineSampleChunks == null ? 20 : outlineSampleChunks;
        rateLimitRetries = rateLimitRetries == null ? 2 : rateLimitRetries;
        rateLimitBackoff = rateLimitBackoff == null ? Duration.ofSeconds(20) : rateLimitBackoff;
        lessonRateLimitRetries = lessonRateLimitRetries == null ? 1 : lessonRateLimitRetries;
        lessonRateLimitBackoff = lessonRateLimitBackoff == null ? Duration.ofSeconds(5) : lessonRateLimitBackoff;
        generationThreads = generationThreads == null ? 2 : generationThreads;
        queueCapacity = queueCapacity == null ? 20 : queueCapacity;
    }

    public int targetLessons() {
        return targetModules * lessonsPerModule;
    }
}
