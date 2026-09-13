package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.StaticQuestionEntry;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.QuestionType;
import com.mockinterview.backend.entity.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * RAG retrieval over the ingested handbook content (PLAN.md §4). The primary question source
 * once ingestion has run; InterviewSessionService falls back to StaticQuestionBankService when
 * this returns empty (e.g. before ingestion has ever been triggered, or a topic/difficulty
 * combination has no ingested content yet).
 *
 * Handbook chunks already carry a clean, human-written question title in their metadata
 * (see ContentIngestionService), so unlike a bare problem title from an unstructured source,
 * no LLM rephrasing call is needed here — the retrieved title is used directly as the prompt.
 */
@Service
@RequiredArgsConstructor
public class QuestionSelectionService {

    private static final int TOP_K = 5;

    private final VectorStore vectorStore;
    private final TopicCatalogService topicCatalogService;

    public Optional<StaticQuestionEntry> pickNext(Topic topic, Difficulty difficulty, Set<String> usedChunkIds) {
        String filter = "topic == '%s' && difficulty == '%s'".formatted(topic.name(), difficulty.name());
        SearchRequest request = SearchRequest.builder()
                .query(seedQuery(topic, difficulty))
                .topK(TOP_K)
                .filterExpression(filter)
                .build();

        List<Document> results = vectorStore.similaritySearch(request);

        return results.stream()
                .filter(doc -> !usedChunkIds.contains(doc.getId()))
                .findFirst()
                .map(doc -> new StaticQuestionEntry(
                        doc.getId(),
                        difficulty,
                        QuestionType.CONCEPTUAL,
                        String.valueOf(doc.getMetadata().getOrDefault("questionTitle", doc.getText())),
                        null, null, null, null, null
                ));
    }

    private String seedQuery(Topic topic, Difficulty difficulty) {
        return "%s interview question, %s difficulty".formatted(
                topicCatalogService.get(topic).label().toLowerCase(),
                difficulty.name().toLowerCase());
    }
}
