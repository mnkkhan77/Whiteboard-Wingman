package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview_sessions")
@Data
public class InterviewSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Topic topic;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty startingDifficulty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Difficulty currentDifficulty;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SessionStatus status = SessionStatus.IN_PROGRESS;

    @Column(nullable = false)
    private int targetQuestionCount;

    @Column(nullable = false)
    private int questionsAsked = 0;

    /** Times the candidate's browser tab lost focus/visibility mid-interview — a lightweight
     *  integrity signal for the report, not an enforcement mechanism (nothing blocks on it). */
    @Column(nullable = false)
    private int tabSwitchCount = 0;

    /** Whether an LLM key was provided at session start — decided once here (not re-derived from
     *  whether a later request happens to carry a key) so which sections run and how coding
     *  answers get graded stays consistent for the whole session. See InterviewSessionService. */
    @Column(nullable = false)
    private boolean llmAvailable = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
