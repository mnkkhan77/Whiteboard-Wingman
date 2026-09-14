package com.mockinterview.backend.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SimpleVectorStore (in-memory) is the pragmatic choice while there's no provisioned Pinecone
 * account/API key — swapping to PineconeVectorStore later is a one-bean change here, everything
 * else (ContentIngestionService, QuestionSelectionService) is written against the VectorStore
 * interface and doesn't care which implementation backs it. See PLAN.md §3.
 *
 * Declared as the concrete SimpleVectorStore (not the VectorStore interface) so
 * ContentIngestionService can call its save/load(File) methods to persist across restarts;
 * QuestionSelectionService and anything else asking for a plain VectorStore is still satisfied
 * by this same bean since SimpleVectorStore is a subtype.
 */
@Configuration
public class SpringAiConfig {

    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }
}
