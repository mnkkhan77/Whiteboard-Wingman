package com.mockinterview.backend.dto;

/** The caller's pack chat token budget for the current month: tokens used so far vs the tier limit. */
public record ChatQuotaDto(long used, long limit) {
}
