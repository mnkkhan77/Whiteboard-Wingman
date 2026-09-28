package com.mockinterview.backend.service;

import com.mockinterview.backend.entity.QuestionType;

import java.util.List;

/**
 * Which sections one topic of a session runs, in order, and how many questions each asks — the
 * shape InterviewSessionService's section/topic transitions walk. Produced by a
 * {@link QuestionSource}; types absent from the plan have a target of 0.
 */
public record SectionPlan(List<Section> sections) {

    public record Section(QuestionType type, int target) {
    }

    public static final SectionPlan EMPTY = new SectionPlan(List.of());

    public SectionPlan {
        sections = List.copyOf(sections);
    }

    public List<QuestionType> order() {
        return sections.stream().map(Section::type).toList();
    }

    public int target(QuestionType type) {
        return sections.stream().filter(s -> s.type() == type).mapToInt(Section::target).findFirst().orElse(0);
    }

    public int total() {
        return sections.stream().mapToInt(Section::target).sum();
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }
}
