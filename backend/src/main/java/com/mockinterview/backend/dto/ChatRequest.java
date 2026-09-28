package com.mockinterview.backend.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * POST /api/packs/{id}/chat body. Trimmed on construction, so validation (and everything after it)
 * sees the trimmed text; the upper length bound is configurable (app.chat.max-message-chars) and
 * therefore checked in PackChatService rather than with a compile-time @Size.
 */
public record ChatRequest(@NotBlank String message) {

    public ChatRequest {
        message = message == null ? null : message.trim();
    }
}
