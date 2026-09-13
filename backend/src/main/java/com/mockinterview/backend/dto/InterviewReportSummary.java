package com.mockinterview.backend.dto;

import java.util.List;

/**
 * Structured output contract for the LLM report-narrative call (PLAN.md §3/§9 Phase 4).
 * strongTopics/weakTopics here are the LLM's read of the per-question breakdown (specific
 * subtopics), distinct from ReportService's own deterministic overall-score-based heuristic.
 */
public record InterviewReportSummary(
        List<String> strongTopics,
        List<String> weakTopics,
        String narrativeSummary
) {
}
