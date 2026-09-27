package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.IngestionSummary;
import com.mockinterview.backend.dto.TopicCatalogEntry;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.jsoup.JsoupDocumentReader;
import org.springframework.ai.reader.jsoup.config.JsoupDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
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
 *
 * Chunks land in PostgreSQL via pgvector (Flyway V14's vector_store table), so they survive a
 * restart with no separate disk cache. Re-running ingestion is idempotent — see {@link #chunkId}
 * and the per-source delete in {@link #ingestAll}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentIngestionService {

    private static final String GITHUB_RAW_BASE =
            "https://raw.githubusercontent.com/mnkkhan77/java-backend-interview-handbook/main/";

    private final VectorStore vectorStore;
    private final TopicCatalogService topicCatalogService;

    private static final Pattern QUESTION_TITLE_PATTERN = Pattern.compile("^Q\\d+\\.\\s*(.+?)\\s*ROI:", Pattern.DOTALL);
    private static final int TITLE_FALLBACK_LENGTH = 150;
    private static final FilterExpressionBuilder FILTER = new FilterExpressionBuilder();

    public IngestionSummary ingestAll() {
        Map<Topic, Integer> chunksByTopic = new EnumMap<>(Topic.class);
        int total = 0;

        for (TopicCatalogEntry entry : topicCatalogService.all()) {
            int countForTopic = 0;
            for (String sourcePath : entry.sourcePaths()) {
                List<Document> documents = readAndTag(entry.topic(), entry.sourceType(), sourcePath);
                if (!documents.isEmpty()) {
                    // Deterministic ids already make unchanged chunks upsert in place; clearing the
                    // source first also drops the tail when a file now has fewer sections than on
                    // the last run. Only done once the new read succeeded, so an unreachable REMOTE
                    // file keeps its previously ingested chunks instead of losing them.
                    vectorStore.delete(FILTER.and(
                            FILTER.eq("topic", entry.topic().name()),
                            FILTER.eq("sourceFile", sourcePath)).build());
                    vectorStore.add(documents);
                }
                countForTopic += documents.size();
                log.info("Ingested {} chunks from {} ({})", documents.size(), sourcePath, entry.topic());
            }
            chunksByTopic.put(entry.topic(), countForTopic);
            total += countForTopic;
        }

        return new IngestionSummary(total, chunksByTopic);
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
            metadata.put("chunkKey", chunkKey(topic, sourcePath, i));

            tagged.add(Document.builder()
                    .id(chunkId(topic, sourcePath, i))
                    .text(text)
                    .metadata(metadata)
                    .build());
        }
        return tagged;
    }

    /** Human-readable identity of a chunk (what its id used to be) — kept in metadata for debugging. */
    private static String chunkKey(Topic topic, String sourcePath, int index) {
        return topic.name() + ":" + sourcePath + ":" + index;
    }

    /**
     * PgVectorStore's id column is a UUID (its default PgIdType, which study-pack chunks sharing
     * the same table also use), and its add() is an upsert on id — so a name-based (v3) UUID of the
     * chunk key makes re-ingesting the same section overwrite its row instead of duplicating it,
     * without switching the whole table to TEXT ids. It also keeps Question.sourceChunkId stable
     * across re-ingestion, so an in-progress session's already-used chunks stay recognisable.
     */
    static String chunkId(Topic topic, String sourcePath, int index) {
        return UUID.nameUUIDFromBytes(chunkKey(topic, sourcePath, index).getBytes(StandardCharsets.UTF_8)).toString();
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
