package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import jakarta.validation.constraints.NotNull;

public record StartSessionRequest(
        @NotNull Topic topic,
        @NotNull Difficulty startingDifficulty,
        Integer questionCount
) {
}
