package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.StaticQuestionEntry;
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
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QuestionSelectionServiceTest {

    @Mock private VectorStore vectorStore;
    @Mock private TopicCatalogService topicCatalogService;

    private QuestionSelectionService service() {
        when(topicCatalogService.get(any())).thenAnswer(inv -> new TopicCatalogEntry(
                inv.getArgument(0), Category.JAVA_BACKEND, "Test Topic",
                TopicCatalogEntry.SourceType.CLASSPATH, List.of("unused.html")));
        return new QuestionSelectionService(vectorStore, topicCatalogService);
    }

    private Document docWithTitle(String id, String title) {
        return Document.builder().id(id).text("full chunk text").metadata(Map.of("questionTitle", title)).build();
    }

    @Test
    void returnsEmptyWhenTheVectorStoreHasNoMatch() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());

        Optional<StaticQuestionEntry> result = service().pickNext(Topic.DSA, Difficulty.EASY, Set.of());

        assertThat(result).isEmpty();
    }

    @Test
    void usesTheIngestedQuestionTitleAsThePromptTextDirectlyWithNoLlmRephrasing() {
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(docWithTitle("chunk-1", "How does a HashMap resolve collisions internally?")));

        Optional<StaticQuestionEntry> result = service().pickNext(Topic.DSA, Difficulty.EASY, Set.of());

        assertThat(result).isPresent();
        assertThat(result.get().promptText()).isEqualTo("How does a HashMap resolve collisions internally?");
        assertThat(result.get().id()).isEqualTo("chunk-1");
        assertThat(result.get().difficulty()).isEqualTo(Difficulty.EASY);
    }

    @Test
    void skipsChunksAlreadyUsedInThisSession() {
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(
                docWithTitle("chunk-1", "Already asked this one"),
                docWithTitle("chunk-2", "A fresh question")
        ));

        Optional<StaticQuestionEntry> result = service().pickNext(Topic.DSA, Difficulty.EASY, Set.of("chunk-1"));

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo("chunk-2");
    }

    @Test
    void filtersByTopicAndDifficulty() {
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        when(vectorStore.similaritySearch(captor.capture())).thenReturn(List.of());

        service().pickNext(Topic.SPRING, Difficulty.HARD, Set.of());

        assertThat(captor.getValue().getFilterExpression().toString()).contains("SPRING").contains("HARD");
    }
}
