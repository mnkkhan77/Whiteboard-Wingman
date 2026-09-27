package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "reports")
@Data
public class Report {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false, unique = true)
    private InterviewSession session;

    @Column(nullable = false)
    private int overallScore;

    @ElementCollection
    @CollectionTable(name = "report_strong_topics", joinColumns = @JoinColumn(name = "report_id"))
    @Column(name = "topic")
    private List<String> strongTopics = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "report_weak_topics", joinColumns = @JoinColumn(name = "report_id"))
    @Column(name = "topic")
    private List<String> weakTopics = new ArrayList<>();

    /** LLM-generated narrative summary — null in Phase 1, added in Phase 4. TEXT, not @Lob — see Answer.answerText. */
    @Column(columnDefinition = "text")
    private String summaryText;

    @Column(nullable = false)
    private int questionCount;

    @Column(nullable = false)
    private double averageDifficultyReached;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** Null until the owner first requests a public share link — generated lazily, see ReportService. */
    @Column(unique = true)
    private String shareToken;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
