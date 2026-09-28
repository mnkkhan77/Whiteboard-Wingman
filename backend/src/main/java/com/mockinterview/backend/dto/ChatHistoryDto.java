package com.mockinterview.backend.dto;

import java.util.List;

/** GET /api/packs/{id}/chat — the latest messages, oldest first, plus the caller's monthly quota. */
public record ChatHistoryDto(List<ChatMessageDto> messages, ChatQuotaDto quota) {
}
