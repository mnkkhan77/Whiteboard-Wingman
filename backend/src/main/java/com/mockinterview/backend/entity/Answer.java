package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "answers")
@Data
public class Answer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false, unique = true)
    private Question question;

    // Plain TEXT, deliberately not @Lob: on PostgreSQL, Hibernate maps an @Lob String to an oid
    // large object (a separate pg_largeobject row read via the LOB API), not the TEXT column Flyway
    // creates, so ddl-auto=validate rejects it. Same for every long-text field in the entities.
    @Column(nullable = false, columnDefinition = "text")
    private String answerText;

    @Column(columnDefinition = "text")
    private String codeSubmission;

    @Column(nullable = false, updatable = false)
    private LocalDateTime submittedAt;

    @PrePersist
    protected void onCreate() {
        this.submittedAt = LocalDateTime.now();
    }
}
