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
 * Reads chunks of a pack by position, straight from the vector_store table — despite the name, not
 * quiz-specific: reused as-is by flashcard-deck and course-outline generation too. Two access
 * patterns: {@link #readSpread} (an even sample across the whole pack, for a question bank, a
 * flashcard deck, or the one course-outline call) and {@link #readRange} (a lesson's own
 * contiguous slice, read lazily when that lesson is first opened).
 *
 * Why by id: the chunk positions to read are chosen up front ({@link #spreadIndices} or a plain
 * range), and their vector ids are deterministic (PackEmbeddingService.vectorId), so one
 * primary-key lookup fetches exactly those rows. The alternatives are worse — a similaritySearch
 * with a packId filter needs a query to embed, returns nearest-to-that-query chunks rather than an
 * even spread or a specific range, and the HNSW index may return fewer rows than asked when
 * filtering; reading every chunk of the pack (up to 6000) to then keep a handful is wasted I/O.
 * The table is Flyway-owned (V14), so its shape is ours to rely on.
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
        return readByIndices(packId, ownerId, spreadIndices(chunkCount, wanted));
    }

    /**
     * Every non-blank chunk in {@code [fromInclusive, toExclusive)}, in document order — a
     * course lesson's assigned slice (PackCourseGenerator), unlike readSpread's even sample across
     * the whole pack. Empty once a slice has no chunks left (e.g. a trailing blank run).
     */
    public List<SourceChunk> readRange(long packId, long ownerId, int fromInclusive, int toExclusive) {
        if (toExclusive <= fromInclusive) {
            return List.of();
        }
        List<Integer> indices = new ArrayList<>(toExclusive - fromInclusive);
        for (int i = fromInclusive; i < toExclusive; i++) {
            indices.add(i);
        }
        return readByIndices(packId, ownerId, indices);
    }

    private List<SourceChunk> readByIndices(long packId, long ownerId, List<Integer> indices) {
        List<UUID> ids = indices.stream()
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
