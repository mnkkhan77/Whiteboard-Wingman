package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One question of a study pack's LLM-generated bank (V19). Only MCQ and CONCEPTUAL exist here —
 * a pack never has coding questions. packId is a plain column, as in PackChatMessage: the bank is
 * always read for an already-authorized pack id, and the pack delete cascades in the database.
 */
@Entity
@Table(name = "pack_quiz_questions")
@Getter
@Setter
public class PackQuizQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pack_id", nullable = false)
    private Long packId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private QuestionType questionType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Difficulty difficulty;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    /** MCQ only: exactly 4 distinct choices, in display order. Null for CONCEPTUAL. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> options;

    /** MCQ only: 0-3. */
    private Integer correctOptionIndex;

    /** MCQ only: why the correct option is correct, shown after answering. */
    @Column(columnDefinition = "text")
    private String explanation;

    /** CONCEPTUAL only: the model answer the grader compares the candidate's answer against. */
    @Column(columnDefinition = "text")
    private String referenceAnswer;

    private Integer sourcePage;

    @Column(length = 500)
    private String sourceSection;

    private Integer sourceChunkIndex;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
