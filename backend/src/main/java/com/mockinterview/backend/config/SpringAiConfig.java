package com.mockinterview.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SimpleVectorStore (in-memory) is the pragmatic choice while there's no provisioned Pinecone
 * account/API key — swapping to PineconeVectorStore later is a one-bean change here, everything
 * else (ContentIngestionService, QuestionSelectionService) is written against the VectorStore
 * interface and doesn't care which implementation backs it. See PLAN.md §3.
 */
@Configuration
public class SpringAiConfig {

    @Bean
    public VectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
