package com.mockinterview.backend.dto;

import java.time.LocalDate;

/** One point on the GET /admin/stats signups-over-time chart (PLAN.md §5), weekStart = that week's Monday. */
public record WeeklySignupCount(LocalDate weekStart, long count) {
}
