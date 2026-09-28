package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    /** The study pack a quiz session (topic STUDY_PACK) draws its questions from; null for every
     *  handbook-topic session. A plain column, not an association: only the pack's title is ever
     *  shown, and PackTitleLookup fetches those in one query per list. Becomes null if the pack is
     *  deleted (V20, ON DELETE SET NULL) — the session itself and its report stay. */
    @Column(name = "pack_id")
    private Long packId;

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

    /** Topics still queued after the currently-active one (topic, above) — a multi-topic "loop"
     *  session pops the next one in here into topic once topic's own sections are exhausted,
     *  resetting currentDifficulty back to startingDifficulty for the fresh topic. Empty for a
     *  single-topic session, which behaves exactly as it always has. */
    @ElementCollection
    @CollectionTable(name = "interview_session_topic_queue", joinColumns = @JoinColumn(name = "session_id"))
    @OrderColumn(name = "queue_index")
    @Enumerated(EnumType.STRING)
    @Column(name = "topic", nullable = false, length = 30)
    private List<Topic> topicQueue = new ArrayList<>();

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

    /** Keyed on the topic rather than packId, so a quiz whose pack was deleted (packId set to
     *  null) is still treated as one — its questions, grading and report stay the pack kind. */
    public boolean isPackSession() {
        return topic == Topic.STUDY_PACK;
    }
}
