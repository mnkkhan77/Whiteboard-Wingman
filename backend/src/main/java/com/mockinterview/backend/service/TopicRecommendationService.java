package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.TopicCatalogEntry;
import com.mockinterview.backend.dto.TopicRecommendation;
import com.mockinterview.backend.dto.TopicRecommendationResponse;
import com.mockinterview.backend.entity.Topic;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Recommends a topic + starting difficulty from a pasted resume/JD, so a candidate doesn't have
 * to manually browse the ~160-topic catalog. Requires the caller's own LLM key (PerRequestChatClientFactory)
 * — there's no non-LLM fallback for free-text analysis.
 */
@Service
@RequiredArgsConstructor
public class TopicRecommendationService {

    private final TopicCatalogService topicCatalogService;
    private final PerRequestChatClientFactory chatClientFactory;

    public TopicRecommendationResponse recommend(String resumeOrJdText, String apiKey,
            PerRequestChatClientFactory.Provider provider, String model) {
        ChatClient chatClient = chatClientFactory.forRequest(apiKey, provider, model);

        String candidateList = topicCatalogService.all().stream()
                .map(TopicRecommendationService::describe)
                .collect(Collectors.joining("\n"));

        TopicRecommendation recommendation = requestRecommendation(chatClient, candidateList, resumeOrJdText, null);
        Topic topic = resolveTopic(recommendation.topicCode());
        if (topic == null) {
            // One retry with the constraint restated, in case the first pass hallucinated a code —
            // never surface an invalid topic code to the frontend.
            recommendation = requestRecommendation(chatClient, candidateList, resumeOrJdText, recommendation.topicCode());
            topic = resolveTopic(recommendation.topicCode());
        }
        if (topic == null) {
            throw new IllegalArgumentException(
                    "Could not determine a valid topic recommendation from the LLM's response. Please try again.");
        }

        return new TopicRecommendationResponse(topic, recommendation.startingDifficulty(), recommendation.rationale());
    }

    private Topic resolveTopic(String topicCode) {
        if (topicCode == null || topicCode.isBlank()) {
            return null;
        }
        try {
            Topic topic = Topic.valueOf(topicCode.trim());
            topicCatalogService.get(topic);
            return topic;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private TopicRecommendation requestRecommendation(ChatClient chatClient, String candidateList,
            String resumeOrJdText, String previousInvalidCode) {
        String system = """
                You are helping a candidate choose which mock-interview topic to practice next, based on
                a resume or job description they paste in. Choose exactly ONE topic from the candidate
                list below — never invent a code that isn't listed — plus a starting difficulty
                (EASY, MEDIUM or HARD) matching how strong their background looks in that area, and a
                short 1-3 sentence rationale for the pick. Return your answer using the required
                structured format only, with the topic's code (not its label) as topicCode.

                Candidate topics (code — label (category)):
                %s
                """.formatted(candidateList);

        String retryNote = previousInvalidCode != null
                ? "\n\nYour previous answer used topicCode \"%s\", which is not one of the codes listed above. You MUST set topicCode to exactly one of those codes, verbatim.".formatted(previousInvalidCode)
                : "";

        String user = """
                Resume or job description:
                %s
                %s
                """.formatted(resumeOrJdText, retryNote);

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .entity(TopicRecommendation.class);
    }

    private static String describe(TopicCatalogEntry entry) {
        return "%s — %s (%s)".formatted(entry.topic(), entry.label(), entry.category());
    }
}
