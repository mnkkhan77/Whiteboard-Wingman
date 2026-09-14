package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * topics is the optional multi-topic "loop" extension: when it carries 2+ entries, the first
 * becomes the session's active topic and the rest are queued (see InterviewSessionService);
 * absent or with fewer than 2 entries, topic alone drives the (single-topic) session exactly as
 * before. topic stays required so every existing caller keeps working unchanged.
 */
public record StartSessionRequest(
        @NotNull Topic topic,
        @NotNull Difficulty startingDifficulty,
        Integer questionCount,
        List<Topic> topics
) {
    public StartSessionRequest(Topic topic, Difficulty startingDifficulty, Integer questionCount) {
        this(topic, startingDifficulty, questionCount, null);
    }
}
