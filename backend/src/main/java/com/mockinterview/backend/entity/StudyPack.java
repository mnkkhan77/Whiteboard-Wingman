package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * A user-uploaded document that the doc-processor parses into chunks and the backend then embeds
 * into the vector store (docs/study-packs-contract.md). The source file itself lives on disk under
 * app.storage.root at {@link #storagePath}; only its metadata and pipeline status live here.
 *
 * Status transitions after upload go through StudyPackRepository's conditional bulk updates rather
 * than save(entity), so a Kafka redelivery or a concurrent DELETE can never resurrect or regress a
 * pack — see PackEmbeddingService.
 */
@Entity
@Table(name = "study_packs")
@Data
public class StudyPack {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User owner;

    @Column(nullable = false)
    private String title;

    /** Original client-side file name (last path segment only), shown back to the user. */
    @Column(nullable = false)
    private String fileName;

    /** Canonical type derived from the sniffed extension, not the client-supplied header. */
    @Column(nullable = false)
    private String contentType;

    @Column(nullable = false, length = 10)
    private String extension;

    /** Relative to app.storage.root, forward slashes: packs/{id}/source.{ext}. Null only between
     *  the insert (which assigns the id the path is built from) and the file write, in one tx. */
    @Column(length = 512)
    private String storagePath;

    @Column(nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StudyPackStatus status = StudyPackStatus.QUEUED;

    private Integer pageCount;

    private Integer chunkCount;

    @Column(length = 50)
    private String parser;

    private Boolean ocrUsed;

    @Column(length = 50)
    private String errorCode;

    @Column(length = 2000)
    private String errorMessage;

    /** The question bank (Phase 4, PackQuizQuestion). Like status, only changed through
     *  StudyPackRepository's conditional updates once the pack exists. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuizStatus quizStatus = QuizStatus.NONE;

    /** Rows currently in the bank — kept when a regeneration fails, since the old bank stays. */
    @Column(nullable = false)
    private int quizQuestionCount = 0;

    /** Safe, user-facing reason for quizStatus FAILED; never exception text. */
    @Column(length = 500)
    private String quizErrorMessage;

    /** The flashcard deck (Phase 5, PackFlashcard). Same conditional-update discipline as quizStatus. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FlashcardStatus flashcardStatus = FlashcardStatus.NONE;

    /** Cards currently in the deck — kept when a regeneration fails, since the old deck stays. */
    @Column(nullable = false)
    private int flashcardCount = 0;

    /** Safe, user-facing reason for flashcardStatus FAILED; never exception text. */
    @Column(length = 500)
    private String flashcardErrorMessage;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
