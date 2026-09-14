package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.TopicRecommendationRequest;
import com.mockinterview.backend.dto.TopicRecommendationResponse;
import com.mockinterview.backend.dto.TopicSummary;
import com.mockinterview.backend.entity.Category;
import com.mockinterview.backend.service.PerRequestChatClientFactory;
import com.mockinterview.backend.service.PerRequestChatClientFactory.Provider;
import com.mockinterview.backend.service.TopicCatalogService;
import com.mockinterview.backend.service.TopicRecommendationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Backs the Start Interview topic picker — one source of truth (TopicCatalogService) instead of
 *  duplicating ~160 topic labels in the frontend. */
@RestController
@RequestMapping("/api/topics")
@RequiredArgsConstructor
public class TopicController {

    private final TopicCatalogService topicCatalogService;
    private final TopicRecommendationService topicRecommendationService;

    @GetMapping
    public Map<Category, List<TopicSummary>> listByCategory() {
        Map<Category, List<TopicSummary>> result = new EnumMap<>(Category.class);
        topicCatalogService.byCategory().forEach((category, entries) ->
                result.put(category, entries.stream().map(TopicSummary::from).toList()));
        return result;
    }

    /** Resume/JD-tailored topic suggestion — requires an LLM key, unlike the rest of this
     *  controller, since there's no non-LLM fallback for free-text analysis. */
    @PostMapping("/recommend")
    public TopicRecommendationResponse recommend(
            @Valid @RequestBody TopicRecommendationRequest request,
            @RequestHeader(value = "X-LLM-Api-Key", required = false, defaultValue = "") String apiKey,
            @RequestHeader(value = "X-LLM-Provider", required = false, defaultValue = "GROQ") String provider,
            @RequestHeader(value = "X-LLM-Model", required = false) String model) {
        return topicRecommendationService.recommend(request.resumeOrJdText(), apiKey, parseProvider(provider), model);
    }

    private Provider parseProvider(String raw) {
        try {
            return Provider.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unsupported X-LLM-Provider: " + raw);
        }
    }
}
