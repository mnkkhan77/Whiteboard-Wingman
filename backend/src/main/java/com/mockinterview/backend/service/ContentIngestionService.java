package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.IngestionSummary;
import com.mockinterview.backend.dto.TopicCatalogEntry;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Admin-only, run-on-demand ingestion (PLAN.md §4, Source A: the handbook's Q&A-structured HTML
 * chapters). Each `.section` div in these files is already one complete, human-authored Q&A
 * unit — question, ROI/frequency line, full answer — so it becomes one chunk verbatim, with no
 * token-splitter needed. Difficulty isn't tagged in the source (ROI turned out to be constant
 * within a file, not a real difficulty signal), so it's bucketed by position in the file: guides
 * like this run foundational-to-advanced, so first-third/middle-third/last-third is a cheap,
 * reasonable proxy — see PLAN.md §4's "cheap difficulty hint" guidance.
 *
 * Which file(s) back which topic comes entirely from TopicCatalogService (topics/catalog.json):
 * CLASSPATH entries are the original vendored chapters, REMOTE entries are fetched live from the
 * handbook's GitHub repo on every ingestion run, so edits to that repo show up here without a
 * redeploy.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentIngestionService {

    private static final String GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/mnkkhan77/java-backend-interview-handbook/main/";

    private final SimpleVectorStore vectorStore;
    private final TopicCatalogService topicCatalogService;

    @Value("${app.vector-store-cache-path:./data/vector-store.json}")
    private String vectorStoreCachePath;

    private static final Pattern QUESTION_TITLE_PATTERN = Pattern.compile("^Q\\d+\\.\\s*(.+?)\\s*ROI:", Pattern.DOTALL);
    private static final int TITLE_FALLBACK_LENGTH = 150;

    /**
     * Restores the in-memory vector store from disk at boot so a restart doesn't silently wipe
     * everything an earlier /api/admin/ingest run populated — ingestion itself stays manual.
     */
    @PostConstruct
    void loadCache() {
        File file = new File(vectorStoreCachePath);
        if (!file.exists()) {
            return; // first-ever boot, or nobody has ingested yet
        }
        try {
            vectorStore.load(file);
            log.info("Restored vector store cache from {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not load vector store cache from {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    public IngestionSummary ingestAll() {
        Map<Topic, Integer> chunksByTopic = new EnumMap<>(Topic.class);
        int total = 0;

        for (TopicCatalogEntry entry : topicCatalogService.all()) {
            int countForTopic = 0;
            for (String sourcePath : entry.sourcePaths()) {
                List<Document> documents = readAndTag(entry.topic(), entry.sourceType(), sourcePath);
                if (!documents.isEmpty()) {
                    vectorStore.add(documents);
                }
                countForTopic += documents.size();
                log.info("Ingested {} chunks from {} ({})", documents.size(), sourcePath, entry.topic());
            }
            chunksByTopic.put(entry.topic(), countForTopic);
            total += countForTopic;
        }

        persistCache();
        return new IngestionSummary(total, chunksByTopic);
    }

    private void persistCache() {
        File file = new File(vectorStoreCachePath);
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try {
            vectorStore.save(file);
            log.info("Persisted vector store cache to {}", file.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not persist vector store cache to {}: {}", file.getAbsolutePath(), e.getMessage());
        }
    }

    private List<Document> readAndTag(Topic topic, TopicCatalogEntry.SourceType sourceType, String sourcePath) {
        JsoupDocumentReaderConfig config = JsoupDocumentReaderConfig.builder()
                .selector(".section")
                .groupByElement(true)
                .separator(" ")
                .charset("UTF-8")
                .build();

        List<Document> rawDocuments;
        try {
            Resource resource = sourceType == TopicCatalogEntry.SourceType.REMOTE
                    ? new UrlResource(GITHUB_RAW_BASE + sourcePath)
                    : new ClassPathResource(sourcePath);
            rawDocuments = new JsoupDocumentReader(resource, config).get();
        } catch (Exception e) {
            log.warn("Skipping {} — could not be read/parsed: {}", sourcePath, e.getMessage());
            return List.of();
        }

        List<Document> tagged = new ArrayList<>(rawDocuments.size());
        int total = rawDocuments.size();
        for (int i = 0; i < total; i++) {
            Document raw = rawDocuments.get(i);
            String text = raw.getText();
            if (text == null || text.isBlank()) {
                continue; // an occasional structural section with no real Q&A content
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("topic", topic.name());
            metadata.put("difficulty", bucketDifficulty(i, total).name());
            metadata.put("sourceFile", sourcePath);
            metadata.put("questionTitle", extractQuestionTitle(text));

            tagged.add(Document.builder()
                    .id(topic.name() + ":" + sourcePath + ":" + i)
                    .text(text)
                    .metadata(metadata)
                    .build());
        }
        return tagged;
    }

    private Difficulty bucketDifficulty(int index, int total) {
        if (total <= 0) return Difficulty.MEDIUM;
        double fraction = (double) index / total;
        if (fraction < 1.0 / 3) return Difficulty.EASY;
        if (fraction < 2.0 / 3) return Difficulty.MEDIUM;
        return Difficulty.HARD;
    }

    private String extractQuestionTitle(String text) {
        Matcher matcher = QUESTION_TITLE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.length() > TITLE_FALLBACK_LENGTH ? text.substring(0, TITLE_FALLBACK_LENGTH) + "..." : text;
    }
}
