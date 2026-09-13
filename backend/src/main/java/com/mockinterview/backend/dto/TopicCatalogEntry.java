package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Category;
import com.mockinterview.backend.entity.Topic;

import java.util.List;

/**
 * One row of src/main/resources/topics/catalog.json — the single source of truth for a topic's
 * category, display label and interview-content source(s). Loaded by TopicCatalogService.
 */
public record TopicCatalogEntry(
        Topic topic,
        Category category,
        String label,
        SourceType sourceType,
        List<String> sourcePaths
) {
    /** CLASSPATH resolves sourcePaths against the classpath (vendored files); REMOTE fetches them
     *  live from the handbook's GitHub repo at ingestion time — see ContentIngestionService. */
    public enum SourceType {
        CLASSPATH,
        REMOTE
    }
}
