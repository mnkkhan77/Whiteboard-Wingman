package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "evaluations")
@Data
public class Evaluation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "answer_id", nullable = false, unique = true)
    private Answer answer;

    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Correctness correctness;

    // TEXT, not @Lob — see the note on Answer.answerText.
    @Column(nullable = false, columnDefinition = "text")
    private String feedback;

    @ElementCollection
    @CollectionTable(name = "evaluation_strengths", joinColumns = @JoinColumn(name = "evaluation_id"))
    @Column(name = "strength")
    private List<String> strengths = new ArrayList<>();

    @ElementCollection
    @CollectionTable(name = "evaluation_weaknesses", joinColumns = @JoinColumn(name = "evaluation_id"))
    @Column(name = "weakness")
    private List<String> weaknesses = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DifficultyDelta recommendedNextDifficulty;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
