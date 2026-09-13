package com.mockinterview.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.dto.StaticQuestionEntry;
import com.mockinterview.backend.dto.TopicCatalogEntry;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.Topic;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Phase 1 question source: a fixed bank loaded from src/main/resources/questions/*.json for the
 * 4 original vendored topics, plus MCQ banks fetched live from the handbook's GitHub repo (same
 * "mnkkhan77/java-backend-interview-handbook" repo ContentIngestionService reads chapters from,
 * under its mcq/ folder — one JSON file per chapter, mirroring that chapter's path) for every
 * other REMOTE topic in the catalog. Replaced by RAG retrieval (QuestionSelectionService) for the
 * verbal round — this class only ever backs the MCQ/coding rounds.
 */
@Slf4j
@Service
public class StaticQuestionBankService {

    private static final Map<Topic, String> RESOURCE_BY_TOPIC = Map.of(
            Topic.JAVA_COLLECTIONS, "questions/java_collections.json",
            Topic.SPRING, "questions/spring.json",
            Topic.DSA, "questions/dsa.json",
            Topic.SYSTEM_DESIGN, "questions/system_design.json"
    );

    private static final String GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/mnkkhan77/java-backend-interview-handbook/main/";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TopicCatalogService topicCatalogService;
    private final Map<Topic, List<StaticQuestionEntry>> bank = new ConcurrentHashMap<>();

    public StaticQuestionBankService(TopicCatalogService topicCatalogService) {
        this.topicCatalogService = topicCatalogService;
    }

    @PostConstruct
    void loadBank() {
        RESOURCE_BY_TOPIC.forEach((topic, path) -> {
            try {
                ClassPathResource resource = new ClassPathResource(path);
                StaticQuestionEntry[] entries = objectMapper.readValue(resource.getInputStream(), StaticQuestionEntry[].class);
                bank.put(topic, List.of(entries));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to load question bank for topic " + topic, e);
            }
        });
    }

    /**
     * Re-fetches the MCQ bank for every REMOTE catalog topic from GitHub. Run alongside
     * ContentIngestionService.ingestAll() (see AdminController's /ingest endpoint) rather than at
     * startup, since it's ~150+ network calls — not every chapter has an MCQ file (narrative/HR
     * chapters were skipped when the banks were authored), so a 404 there just means that topic
     * stays verbal-only, not an ingestion failure.
     */
    public void refreshRemoteBanks() {
        for (TopicCatalogEntry entry : topicCatalogService.all()) {
            if (entry.sourceType() != TopicCatalogEntry.SourceType.REMOTE) {
                continue;
            }
            List<StaticQuestionEntry> merged = new ArrayList<>();
            for (String sourcePath : entry.sourcePaths()) {
                String mcqPath = "mcq/" + sourcePath.replaceFirst("\\.html$", ".json");
                try {
                    UrlResource resource = new UrlResource(GITHUB_RAW_BASE + mcqPath);
                    StaticQuestionEntry[] entries = objectMapper.readValue(resource.getInputStream(), StaticQuestionEntry[].class);
                    merged.addAll(List.of(entries));
                } catch (IOException e) {
                    log.debug("No MCQ bank for {} ({}) — topic stays verbal-only", entry.topic(), mcqPath);
                }
            }
            if (!merged.isEmpty()) {
                bank.put(entry.topic(), merged);
            }
        }
    }

    /**
     * Picks the next question of the given type for the given topic/difficulty, avoiding ids
     * already used in this session. Falls back to the closest available difficulty, then to any
     * unused question of that type for the topic, if the exact combination is exhausted.
     */
    public StaticQuestionEntry pickNext(Topic topic, Difficulty difficulty, QuestionType type, Set<String> usedIds) {
        List<StaticQuestionEntry> pool = bank.getOrDefault(topic, List.of()).stream()
                .filter(q -> q.questionType() == type)
                .toList();

        Optional<StaticQuestionEntry> exact = pool.stream()
                .filter(q -> q.difficulty() == difficulty && !usedIds.contains(q.id()))
                .findFirst();
        if (exact.isPresent()) {
            return exact.get();
        }

        Optional<StaticQuestionEntry> anyUnused = pool.stream()
                .filter(q -> !usedIds.contains(q.id()))
                .findFirst();
        if (anyUnused.isPresent()) {
            return anyUnused.get();
        }

        throw new IllegalStateException("Question bank exhausted for topic " + topic + " (" + type + ")");
    }

    /** Used to size the MCQ/coding sections of a session — those sections always use every
     *  available question of that type for the topic, unlike the verbal section's user-chosen count. */
    public int countByType(Topic topic, QuestionType type) {
        return (int) bank.getOrDefault(topic, List.of()).stream().filter(q -> q.questionType() == type).count();
    }
}
