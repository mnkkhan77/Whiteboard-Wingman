package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.StaticQuestionEntry;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs against the real pgvector container and Flyway's V14 vector_store table (see
 * application-test.yml), with the real local embedding model — the things a mocked VectorStore
 * can't prove: that QuestionSelectionService's "topic == 'X' && difficulty == 'Y'" filter string
 * actually translates to a working jsonpath over the jsonb metadata, and that re-adding a document
 * with the same UUID id upserts instead of duplicating (what makes re-ingestion idempotent).
 *
 * Same annotations as the controller tests on purpose, so Spring reuses their cached context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PgVectorStoreIntegrationTest {

    @Autowired private VectorStore vectorStore;
    @Autowired private QuestionSelectionService questionSelectionService;
    @Autowired private JdbcTemplate jdbcTemplate;

    private final String springHardId = UUID.randomUUID().toString();
    private final String springEasyId = UUID.randomUUID().toString();
    private final String dsaHardId = UUID.randomUUID().toString();

    @AfterEach
    void removeTestRows() {
        // The container is shared by every test class in the run — leftover chunks would make
        // other tests' sessions start from RAG instead of the static bank.
        vectorStore.delete(List.of(springHardId, springEasyId, dsaHardId));
    }

    private Document chunk(String id, Topic topic, Difficulty difficulty, String title) {
        return Document.builder()
                .id(id)
                .text(title + " — full answer text.")
                .metadata(Map.of("topic", topic.name(), "difficulty", difficulty.name(), "questionTitle", title))
                .build();
    }

    @Test
    void topicAndDifficultyFilterOnlyReturnsMatchingChunks() {
        vectorStore.add(List.of(
                chunk(springHardId, Topic.SPRING, Difficulty.HARD, "How does Spring resolve circular dependencies?"),
                chunk(springEasyId, Topic.SPRING, Difficulty.EASY, "What is a Spring bean?"),
                chunk(dsaHardId, Topic.DSA, Difficulty.HARD, "Explain a segment tree.")));

        Optional<StaticQuestionEntry> picked = questionSelectionService.pickNext(Topic.SPRING, Difficulty.HARD, Set.of());

        assertThat(picked).isPresent();
        assertThat(picked.get().id()).isEqualTo(springHardId);
        assertThat(picked.get().promptText()).isEqualTo("How does Spring resolve circular dependencies?");

        // The only SPRING/HARD chunk is already used -> nothing else may leak through the filter.
        assertThat(questionSelectionService.pickNext(Topic.SPRING, Difficulty.HARD, Set.of(springHardId))).isEmpty();
    }

    @Test
    void reAddingTheSameIdUpsertsInsteadOfDuplicating() {
        vectorStore.add(List.of(chunk(springHardId, Topic.SPRING, Difficulty.HARD, "Old title")));
        vectorStore.add(List.of(chunk(springHardId, Topic.SPRING, Difficulty.HARD, "New title")));

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM vector_store WHERE id = ?::uuid", String.class, springHardId);
        assertThat(contents).containsExactly("New title — full answer text.");
    }
}
