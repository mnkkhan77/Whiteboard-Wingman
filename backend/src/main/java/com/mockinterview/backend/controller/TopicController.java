package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.TopicSummary;
import com.mockinterview.backend.entity.Category;
import com.mockinterview.backend.service.TopicCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping
    public Map<Category, List<TopicSummary>> listByCategory() {
        Map<Category, List<TopicSummary>> result = new EnumMap<>(Category.class);
        topicCatalogService.byCategory().forEach((category, entries) ->
                result.put(category, entries.stream().map(TopicSummary::from).toList()));
        return result;
    }
}
