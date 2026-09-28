package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Starts either a handbook-topic interview or a study-pack quiz: exactly one of topic / packId
 * (InterviewSessionService rejects both or neither with a 400).
 *
 * topics is the optional multi-topic "loop" extension: when it carries 2+ entries, the first
 * becomes the session's active topic and the rest are queued (see InterviewSessionService);
 * absent or with fewer than 2 entries, topic alone drives the (single-topic) session exactly as
 * before. packId starts a quiz from that pack's question bank (docs/study-packs-contract.md
 * "Quiz from a pack"); questionCount is then 2-20 and split into verbal + multiple choice.
 */
public record StartSessionRequest(
        Topic topic,
        @NotNull Difficulty startingDifficulty,
        Integer questionCount,
        List<Topic> topics,
        Long packId
) {
    public StartSessionRequest(Topic topic, Difficulty startingDifficulty, Integer questionCount) {
        this(topic, startingDifficulty, questionCount, null, null);
    }

    public StartSessionRequest(Topic topic, Difficulty startingDifficulty, Integer questionCount, List<Topic> topics) {
        this(topic, startingDifficulty, questionCount, topics, null);
    }

    public static StartSessionRequest forPack(Long packId, Difficulty startingDifficulty, Integer questionCount) {
        return new StartSessionRequest(null, startingDifficulty, questionCount, null, packId);
    }
}
