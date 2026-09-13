package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Topic;

import java.util.Map;

public record IngestionSummary(int totalChunks, Map<Topic, Integer> chunksByTopic) {
}
