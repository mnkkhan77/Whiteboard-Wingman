package com.mockinterview.backend.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;

/**
 * Pack-chat tokens a user spent in one calendar month (UTC). Only ever read through JPA; writes go
 * through LlmUsageRepository's native upsert so concurrent increments are atomic.
 */
@Entity
@Table(name = "llm_usage")
@Getter
@Setter
@NoArgsConstructor
public class LlmUsage {

    @EmbeddedId
    private Key id;

    @Column(nullable = false)
    private long tokensUsed;

    @Embeddable
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Key implements Serializable {

        @Column(name = "user_id", nullable = false)
        private Long userId;

        /** First day of the month the tokens were spent in. */
        @Column(name = "period_start", nullable = false)
        private LocalDate periodStart;
    }
}
