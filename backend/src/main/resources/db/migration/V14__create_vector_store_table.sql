-- Spring AI PgVectorStore's table, owned by Flyway rather than created by PgVectorStore itself
-- (spring.ai.vectorstore.pgvector.initialize-schema is false), so the schema is versioned and
-- reviewable like every other table. Mirrors what PgVectorStore 1.0.1 would create for
-- id-type UUID / HNSW / COSINE_DISTANCE, with two deliberate differences:
--   * the id default uses the built-in gen_random_uuid() (PG 13+) instead of uuid-ossp's
--     uuid_generate_v4(), so no extra extension is needed — ContentIngestionService always sets
--     ids explicitly anyway;
--   * metadata is jsonb instead of json — PgVectorStore always writes it as ?::jsonb and filters
--     with metadata::jsonb @@ jsonpath, so jsonb avoids a re-parse per row and lets the GIN index
--     below serve metadata filters (e.g. deleting every chunk of one source file or study pack).
-- vector(384) matches the local all-MiniLM-L6-v2 transformers embedding model; changing the
-- embedding model means a new migration, not an edit here.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE vector_store (
    id UUID DEFAULT gen_random_uuid() PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(384)
);

-- Same index name PgVectorStore uses for its default table, so behaviour matches its own DDL.
CREATE INDEX spring_ai_vector_index ON vector_store USING hnsw (embedding vector_cosine_ops);

CREATE INDEX idx_vector_store_metadata ON vector_store USING gin (metadata jsonb_path_ops);
