package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One Kafka message not yet confirmed sent (V23) — see the migration's comment for the pattern.
 * Written by whichever service owns the business transaction (e.g. StudyPackService.upload, in the
 * same transaction as the StudyPack insert); read and sent by OutboxPublisher.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@Setter
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, length = 100)
    private String messageKey;

    @Column(nullable = false, columnDefinition = "text")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(nullable = false)
    private int attempts = 0;

    @Column(length = 1000)
    private String lastError;

    /** The HTTP request's correlation id (CorrelationIdFilter), sent as a Kafka header so logs for
     *  the original request and the async Kafka handling of it can be grepped together. Null when
     *  the row was written outside a request (there is none yet, but the shape allows for it). */
    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime lastAttemptAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
