package com.mockinterview.backend.dto;

/** Tokens one pack chat answer cost (the "usage" object of the SSE done event). */
public record ChatUsageDto(int promptTokens, int completionTokens, int totalTokens) {

    public static final ChatUsageDto ZERO = new ChatUsageDto(0, 0, 0);

    public static ChatUsageDto of(int promptTokens, int completionTokens) {
        return new ChatUsageDto(promptTokens, completionTokens, promptTokens + completionTokens);
    }
}
