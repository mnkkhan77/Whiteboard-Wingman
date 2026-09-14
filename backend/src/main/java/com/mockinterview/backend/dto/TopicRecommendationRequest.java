package com.mockinterview.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record TopicRecommendationRequest(
        @NotBlank String resumeOrJdText
) {
}
