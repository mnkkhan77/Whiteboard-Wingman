package com.mockinterview.backend.config;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * The VectorStore itself is Spring AI's auto-configured PgVectorStore (settings under
 * spring.ai.vectorstore.pgvector in application.yml, table owned by Flyway V14) — everything
 * else (ContentIngestionService, QuestionSelectionService, study packs) injects the plain
 * VectorStore interface, so there's no store bean to declare here.
 *
 * What does need overriding is how PgVectorStore batches documents for embedding. Its default,
 * TokenCountBatchingStrategy, is sized for OpenAI's 8191-token embedding input limit and throws
 * outright ("Tokens in a single document exceeds the maximum number of allowed input tokens") on
 * any single document over it — which several long handbook sections are. That limit doesn't
 * apply to the local all-MiniLM-L6-v2 transformers model we actually embed with: its tokenizer
 * (tokenizer.json) truncates and pads every input to a fixed 128 tokens, exactly as it did back when
 * SimpleVectorStore embedded one document at a time with no batching check at all. So batches
 * are just fixed-size chunks of documents, bounding memory per ONNX call; the autoconfig's
 * default is @ConditionalOnMissingBean, so this bean replaces it.
 */
@Configuration
public class SpringAiConfig {

    private static final int EMBEDDING_BATCH_SIZE = 64;

    @Bean
    public BatchingStrategy embeddingBatchingStrategy() {
        return documents -> {
            List<List<Document>> batches = new ArrayList<>();
            for (int i = 0; i < documents.size(); i += EMBEDDING_BATCH_SIZE) {
                batches.add(documents.subList(i, Math.min(i + EMBEDDING_BATCH_SIZE, documents.size())));
            }
            return batches;
        };
    }
}
