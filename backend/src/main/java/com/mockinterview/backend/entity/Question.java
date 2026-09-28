package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "questions")
@Data
public class Question {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private InterviewSession session;

    @Column(nullable = false)
    private int sequenceNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Topic topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty difficulty;

    // TEXT, not @Lob — see the note on Answer.answerText. Applies to every text column below too.
    @Column(nullable = false, columnDefinition = "text")
    private String promptText;

    /** Traceability to the vector-store document this came from, once RAG is wired in (Phase 2). Null for the Phase 1 static bank. */
    private String sourceChunkId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType questionType;

    /** MCQ only: the answer choices, in display order. Empty for CONCEPTUAL/CODING. */
    @ElementCollection
    @CollectionTable(name = "question_options", joinColumns = @JoinColumn(name = "question_id"))
    @OrderColumn(name = "option_index")
    @Column(name = "option_text", length = 1000)
    private List<String> options = new ArrayList<>();

    /** MCQ only: 0-based index into options. Never exposed to the frontend before the answer is submitted. */
    private Integer correctOptionIndex;

    /** MCQ only: shown in the feedback after the candidate answers. */
    @Column(columnDefinition = "text")
    private String explanation;

    /** CODING only: describes the stdin/stdout contract the candidate's program must follow, so Run Code can validate it. */
    @Column(columnDefinition = "text")
    private String ioFormat;

    /** CODING only: sample input/output pairs used by the Run Code feature. Empty if this question has none. */
    @ElementCollection
    @CollectionTable(name = "question_test_cases", joinColumns = @JoinColumn(name = "question_id"))
    @OrderColumn(name = "case_index")
    private List<TestCase> testCases = new ArrayList<>();

    /** Pack quiz CONCEPTUAL only: the bank's model answer, given to the grader. Server-side only. */
    @Column(columnDefinition = "text")
    private String referenceAnswer;

    /** Pack quiz only: where in the document the question comes from (feedback points back here). */
    private Integer sourcePage;

    @Column(length = 500)
    private String sourceSection;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }

    /** "page 3, Chapter 2 > Transactions" for a pack question, null for a handbook question. */
    public String sourceReference() {
        String page = sourcePage != null ? "page " + sourcePage : null;
        String section = sourceSection != null && !sourceSection.isBlank() ? sourceSection : null;
        if (page == null && section == null) {
            return null;
        }
        return page == null ? section : section == null ? page : page + ", " + section;
    }

    @Embeddable
    @Data
    public static class TestCase {
        @Column(columnDefinition = "text")
        private String input;

        @Column(nullable = false, columnDefinition = "text")
        private String expectedOutput;
    }
}
