package com.mockinterview.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.dto.TopicCatalogEntry;
import com.mockinterview.backend.entity.Category;
import com.mockinterview.backend.entity.Topic;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Loads src/main/resources/topics/catalog.json — the single source of truth for each Topic's
 * category, display label and interview-content source(s) (see TopicCatalogEntry). Backs both
 * ContentIngestionService (which file(s) to ingest) and GET /api/topics (what the frontend's
 * topic picker renders), so the two never drift apart.
 */
@Service
public class TopicCatalogService {

    private static final String CATALOG_RESOURCE = "topics/catalog.json";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private List<TopicCatalogEntry> entries;
    private Map<Topic, TopicCatalogEntry> byTopic;

    @PostConstruct
    void load() {
        try {
            ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE);
            entries = List.of(objectMapper.readValue(resource.getInputStream(), TopicCatalogEntry[].class));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load topic catalog from " + CATALOG_RESOURCE, e);
        }
        byTopic = new EnumMap<>(Topic.class);
        for (TopicCatalogEntry entry : entries) {
            byTopic.put(entry.topic(), entry);
        }
    }

    public List<TopicCatalogEntry> all() {
        return entries;
    }

    public TopicCatalogEntry get(Topic topic) {
        TopicCatalogEntry entry = byTopic.get(topic);
        if (entry == null) {
            throw new IllegalArgumentException("No catalog entry for topic " + topic);
        }
        return entry;
    }

    public Map<Category, List<TopicCatalogEntry>> byCategory() {
        Map<Category, List<TopicCatalogEntry>> grouped = new EnumMap<>(Category.class);
        for (TopicCatalogEntry entry : entries) {
            grouped.computeIfAbsent(entry.category(), c -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }
}
