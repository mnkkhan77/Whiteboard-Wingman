package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.IngestionSummary;
import com.mockinterview.backend.dto.TopicCatalogEntry;
import com.mockinterview.backend.entity.Category;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Runs against the real handbook HTML files bundled under src/main/resources/interview-content
 * (no mocking of the HTML parsing itself) — only the VectorStore and the topic catalog are
 * mocked, the latter stubbed down to the same 4 classpath-vendored topics the catalog originally
 * had, so this stays a fast, fully offline test even though the real catalog now also lists ~160
 * topics fetched live from GitHub (see ContentIngestionService).
 */
@ExtendWith(MockitoExtension.class)
class ContentIngestionServiceTest {

    @Mock private SimpleVectorStore vectorStore;
    @Mock private TopicCatalogService topicCatalogService;

    private static final List<TopicCatalogEntry> CLASSPATH_CATALOG = List.of(
            new TopicCatalogEntry(Topic.JAVA_COLLECTIONS, Category.JAVA_BACKEND, "Java Collections",
                    TopicCatalogEntry.SourceType.CLASSPATH, List.of("interview-content/java_collections/04-COLLECTIONS.html")),
            new TopicCatalogEntry(Topic.SPRING, Category.JAVA_BACKEND, "Spring",
                    TopicCatalogEntry.SourceType.CLASSPATH, List.of(
                            "interview-content/spring/09-SPRING-CORE.html",
                            "interview-content/spring/10-SPRING-BOOT.html")),
            new TopicCatalogEntry(Topic.DSA, Category.JAVA_BACKEND, "Data Structures & Algorithms",
                    TopicCatalogEntry.SourceType.CLASSPATH, List.of("interview-content/dsa/29-DATA-STRUCTURES-AND-ALGORITHMS.html")),
            new TopicCatalogEntry(Topic.SYSTEM_DESIGN, Category.JAVA_BACKEND, "System Design",
                    TopicCatalogEntry.SourceType.CLASSPATH, List.of("interview-content/system_design/20-SYSTEM-DESIGN-FOR-4-YEAR-BACKEND-ENGINEER.html"))
    );

    private ContentIngestionService service() {
        when(topicCatalogService.all()).thenReturn(CLASSPATH_CATALOG);
        return new ContentIngestionService(vectorStore, topicCatalogService);
    }

    @Test
    void ingestsRealContentForEveryTopicWithMetadataTagging() {
        IngestionSummary summary = service().ingestAll();

        assertThat(summary.totalChunks()).isGreaterThan(0);
        for (TopicCatalogEntry entry : CLASSPATH_CATALOG) {
            assertThat(summary.chunksByTopic().get(entry.topic()))
                    .as("chunk count for %s", entry.topic())
                    .isGreaterThan(0);
        }

        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, times(CLASSPATH_CATALOG.size() + 1 /* SPRING has 2 source files */)).add(captor.capture());

        Document firstBatchFirstDoc = captor.getAllValues().get(0).get(0);
        assertThat(firstBatchFirstDoc.getMetadata()).containsKeys("topic", "difficulty", "sourceFile", "questionTitle");
        assertThat(firstBatchFirstDoc.getMetadata().get("difficulty")).isIn(
                Difficulty.EASY.name(), Difficulty.MEDIUM.name(), Difficulty.HARD.name());
        // Position-based bucketing: the very first chunk in a file should be the easiest.
        assertThat(firstBatchFirstDoc.getMetadata().get("difficulty")).isEqualTo(Difficulty.EASY.name());
    }

    @Test
    void extractsACleanQuestionTitleRatherThanTheRawQnRoiPrefixedText() {
        ContentIngestionService service = service();
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);

        service.ingestAll();

        verify(vectorStore, times(5)).add(captor.capture());
        Document someDoc = captor.getAllValues().stream()
                .flatMap(List::stream)
                .findFirst()
                .orElseThrow();

        String title = (String) someDoc.getMetadata().get("questionTitle");
        assertThat(title).doesNotContain("ROI:");
        assertThat(title).doesNotMatch("^Q\\d+\\..*");
    }
}
