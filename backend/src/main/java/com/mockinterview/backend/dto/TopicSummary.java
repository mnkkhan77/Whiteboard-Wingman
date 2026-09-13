package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Category;
import com.mockinterview.backend.entity.Topic;

/** Public-facing view of a TopicCatalogEntry for GET /api/topics — no source/ingestion details. */
public record TopicSummary(Topic topic, Category category, String label) {
    public static TopicSummary from(TopicCatalogEntry entry) {
        return new TopicSummary(entry.topic(), entry.category(), entry.label());
    }
}
