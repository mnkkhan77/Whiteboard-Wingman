package com.mockinterview.backend.dto;

public record SessionStartResponse(Long sessionId, QuestionResponse firstQuestion) {
}
