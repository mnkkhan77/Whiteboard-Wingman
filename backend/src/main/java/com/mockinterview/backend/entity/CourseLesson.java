package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One lesson of a study pack's LLM-generated course outline (V22). A module is not a separate
 * entity — moduleIndex/moduleTitle group lessons the same way a quiz bank has no separate
 * "section" row (PackQuizQuestion.questionType groups instead). Lessons are assigned a contiguous,
 * non-overlapping slice of the pack's chunks at generation time (sourceChunkStart/End); that
 * slice's actual content is only read — and the lesson's {@code content} filled in, one LLM call,
 * charged to the owner's quota — the first time the lesson is opened. completed is plain per-pack
 * state: like a flashcard's SM-2 schedule, a pack is only ever studied by its own owner.
 */
@Entity
@Table(name = "pack_course_lessons")
@Getter
@Setter
public class CourseLesson {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pack_id", nullable = false)
    private Long packId;

    private int moduleIndex;

    @Column(length = 150, nullable = false)
    private String moduleTitle;

    private int lessonIndexInModule;

    @Column(length = 200, nullable = false)
    private String title;

    @Column(length = 400, nullable = false)
    private String summary;

    /** Filled in lazily (null until the lesson is first opened). */
    @Column(columnDefinition = "text")
    private String content;

    /** Inclusive start / exclusive end into the pack's chunk indices (PackEmbeddingService.vectorId). */
    private int sourceChunkStart;
    private int sourceChunkEnd;

    /** Set together with content, from the lesson's first source chunk. */
    private Integer sourcePage;

    @Column(length = 500)
    private String sourceSection;

    @Column(nullable = false)
    private boolean completed = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
