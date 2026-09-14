package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.TopicRecommendation;
import com.mockinterview.backend.dto.TopicRecommendationResponse;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Topic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Uses the real TopicCatalogService (loaded from the actual catalog.json, same as production)
 * rather than a mock, so the "topic code must exist in the real catalog" validation this service
 * performs is exercised faithfully — only the LLM call itself is stubbed, following ReportServiceTest's
 * ChatClient-mocking style.
 */
@ExtendWith(MockitoExtension.class)
class TopicRecommendationServiceTest {

    @Mock private PerRequestChatClientFactory chatClientFactory;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private TopicRecommendationService service;

    @BeforeEach
    void setUp() {
        TopicCatalogService topicCatalogService = new TopicCatalogService();
        topicCatalogService.load();
        service = new TopicRecommendationService(topicCatalogService, chatClientFactory);
    }

    private void stubLlmToReturn(TopicRecommendation first, TopicRecommendation... rest) {
        when(chatClientFactory.forRequest(anyString(), any(), any())).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.entity(TopicRecommendation.class)).thenReturn(first, rest);
    }

    private TopicRecommendationResponse recommend(String text) {
        return service.recommend(text, "fake-key", PerRequestChatClientFactory.Provider.GROQ, null);
    }

    @Test
    void aValidRecommendationIsParsedAndPassedThrough() {
        stubLlmToReturn(new TopicRecommendation("DSA", Difficulty.MEDIUM, "Strong DSA background on the resume."));

        TopicRecommendationResponse response = recommend("5 years solving algorithm problems in interviews");

        assertThat(response.topic()).isEqualTo(Topic.DSA);
        assertThat(response.startingDifficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(response.rationale()).isEqualTo("Strong DSA background on the resume.");
        verify(chatClientFactory, times(1)).forRequest(anyString(), any(), any());
        verify(callResponseSpec, times(1)).entity(TopicRecommendation.class);
    }

    @Test
    void aHallucinatedTopicCodeIsRetriedOnceAndSucceedsIfTheRetryIsValid() {
        stubLlmToReturn(
                new TopicRecommendation("NOT_A_REAL_TOPIC_CODE", Difficulty.EASY, "bad first guess"),
                new TopicRecommendation("SPRING", Difficulty.EASY, "Spring Boot experience on the resume.")
        );

        TopicRecommendationResponse response = recommend("Spring Boot backend developer");

        assertThat(response.topic()).isEqualTo(Topic.SPRING);
        assertThat(response.rationale()).isEqualTo("Spring Boot experience on the resume.");
        // Same ChatClient reused for the retry — no need to rebuild it from the API key twice.
        verify(chatClientFactory, times(1)).forRequest(anyString(), any(), any());
        verify(callResponseSpec, times(2)).entity(TopicRecommendation.class);
    }

    @Test
    void aHallucinatedTopicCodeThatPersistsAfterRetryFailsCleanlyInsteadOfCrashing() {
        stubLlmToReturn(
                new TopicRecommendation("NOT_A_REAL_TOPIC_CODE", Difficulty.EASY, "bad first guess"),
                new TopicRecommendation("STILL_NOT_A_REAL_CODE", Difficulty.EASY, "bad retry too")
        );

        assertThatThrownBy(() -> recommend("some resume text"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(callResponseSpec, times(2)).entity(TopicRecommendation.class);
    }

    @Test
    void aMissingApiKeyIsRejectedWithAClearErrorInsteadOfSilentlyDegrading() {
        TopicCatalogService topicCatalogService = new TopicCatalogService();
        topicCatalogService.load();
        TopicRecommendationService realFactoryService =
                new TopicRecommendationService(topicCatalogService, new PerRequestChatClientFactory());

        assertThatThrownBy(() -> realFactoryService.recommend(
                "some resume text", "", PerRequestChatClientFactory.Provider.GROQ, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-LLM-Api-Key");
    }
}
