package com.mockinterview.backend.entity;

/**
 * A study pack's course outline status (docs/study-packs-contract.md "Course from a pack"):
 * NONE -> GENERATING -> READY or FAILED; READY and FAILED can go back to GENERATING (regenerate /
 * retry). GENERATING is only ever entered through StudyPackRepository.startCourseGeneration, a
 * conditional update, so two simultaneous "generate" clicks can't start two jobs.
 */
public enum CourseStatus {
    NONE, GENERATING, READY, FAILED
}
