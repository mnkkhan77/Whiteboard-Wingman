package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One card of a study pack's LLM-generated flashcard deck (V21), plus its own SM-2 spaced-repetition
 * schedule. A pack is only ever studied by its owner, so unlike a quiz bank (whose questions are
 * graded per session) a card's schedule lives directly on the row — there is no separate per-user
 * review-state table.
 */
@Entity
@Table(name = "pack_flashcards")
@Getter
@Setter
public class PackFlashcard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pack_id", nullable = false)
    private Long packId;

    @Column(nullable = false, columnDefinition = "text")
    private String front;

    @Column(nullable = false, columnDefinition = "text")
    private String back;

    private Integer sourcePage;

    @Column(length = 500)
    private String sourceSection;

    private Integer sourceChunkIndex;

    /** SM-2 easiness factor, never below 1.3. */
    @Column(nullable = false)
    private double easeFactor = 2.5;

    /** SM-2 current interval in days; 0 means never reviewed. */
    @Column(nullable = false)
    private int intervalDays = 0;

    /** SM-2 consecutive correct (quality >= 3) reviews; reset to 0 on a lapse. */
    @Column(nullable = false)
    private int repetitions = 0;

    /** When this card is next due for review; a brand-new card is due immediately. */
    @Column(nullable = false)
    private LocalDateTime dueAt;

    private LocalDateTime lastReviewedAt;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        if (this.dueAt == null) {
            this.dueAt = this.createdAt;
        }
    }

}
