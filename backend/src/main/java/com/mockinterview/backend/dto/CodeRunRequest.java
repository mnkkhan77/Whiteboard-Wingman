package com.mockinterview.backend.dto;

import jakarta.validation.constraints.NotBlank;

public record CodeRunRequest(
        @NotBlank String language,
        @NotBlank String code
) {
}
