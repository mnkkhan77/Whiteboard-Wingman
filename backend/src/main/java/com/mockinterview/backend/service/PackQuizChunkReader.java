package com.mockinterview.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Reads the chunks a question bank is generated from, spread evenly across the whole pack.
 *
 * Why by id, straight from the vector_store table: the chunk positions to read are chosen up front
 * ({@link #spreadIndices}), and their vector ids are deterministic
 * (PackEmbeddingService.vectorId), so one primary-key lookup fetches exactly those ~12 rows. The
 * alternatives are worse — a similaritySearch with a packId filter needs a query to embed, returns
 * nearest-to-that-query chunks rather than an even spread, and the HNSW index may return fewer
 * rows than asked when filtering; reading every chunk of the pack (up to 6000) to then keep 12 is
 * wasted I/O. The table is Flyway-owned (V14), so its shape is ours to rely on.
 *
 * The packId/ownerId match on the metadata is defense in depth, as in PackRetrievalService.
 */
@Component
@RequiredArgsConstructor
public class PackQuizChunkReader {

    /** One chunk of the document, in document order (chunkIndex = position in chunks.json). */
    public record SourceChunk(int chunkIndex, String text, Integer page, Integer pageEnd, String section) {
    }

    private static final String SQL = """
            SELECT content, metadata::text AS metadata FROM vector_store
            WHERE id IN (:ids) AND metadata->>'packId' = :packId AND metadata->>'ownerId' = :ownerId
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * {@code wanted} positions out of {@code chunkCount}, evenly spread: the middle of each of
     * {@code wanted} equal slices, so both the start and the end of the document are covered.
     */
    static List<Integer> spreadIndices(int chunkCount, int wanted) {
        int k = Math.min(chunkCount, wanted);
        List<Integer> indices = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            indices.add((int) ((i + 0.5) * chunkCount / k));
        }
        return indices;
    }

    /** Up to {@code wanted} non-blank chunks of the pack, in document order. Blank chunks were never
     *  embedded (PackEmbeddingService.toDocuments), so such a position just yields nothing. */
    public List<SourceChunk> readSpread(long packId, long ownerId, int chunkCount, int wanted) {
        if (chunkCount <= 0 || wanted <= 0) {
            return List.of();
        }
        List<UUID> ids = spreadIndices(chunkCount, wanted).stream()
                .map(i -> UUID.fromString(PackEmbeddingService.vectorId(packId, i)))
                .toList();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("packId", String.valueOf(packId))
                .addValue("ownerId", String.valueOf(ownerId));
        List<SourceChunk> chunks = jdbc.query(SQL, params, (rs, row) -> toChunk(rs.getString("content"), rs.getString("metadata")));
        return chunks.stream()
                .filter(c -> c.text() != null && !c.text().isBlank())
                .sorted(Comparator.comparingInt(SourceChunk::chunkIndex))
                .toList();
    }

    private SourceChunk toChunk(String content, String metadataJson) {
        try {
            JsonNode metadata = objectMapper.readTree(metadataJson == null ? "{}" : metadataJson);
            return new SourceChunk(
                    metadata.path("chunkIndex").asInt(0),
                    content,
                    intOrNull(metadata.get("page")),
                    intOrNull(metadata.get("pageEnd")),
                    metadata.hasNonNull("section") ? metadata.get("section").asText() : null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable vector metadata", e);
        }
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.intValue() : null;
    }
}
