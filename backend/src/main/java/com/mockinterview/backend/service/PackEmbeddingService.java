package com.mockinterview.backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.config.TierProperties;
import com.mockinterview.backend.config.TierProperties.TierLimits;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.kafka.DocumentFailedEvent;
import com.mockinterview.backend.kafka.DocumentParsedEvent;
import com.mockinterview.backend.kafka.PackChunk;
import com.mockinterview.backend.repository.StudyPackRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Backend half of the Study Pack pipeline: turns the doc-processor's parsed/failed events into
 * pack status changes and, for parsed, embeds the chunks into the vector store.
 *
 * Kafka delivers at least once, so both handlers are idempotent:
 * - a pack that no longer exists (deleted) or is already READY/FAILED is ignored;
 * - every status change is a conditional update (StudyPackRepository), so a duplicate delivery
 *   racing the first one can't move a pack backwards;
 * - vector ids are deterministic per (pack, chunk position), so re-embedding a redelivered event
 *   (e.g. after a crash mid-embedding, while the pack is still EMBEDDING) overwrites rather than
 *   duplicates — and DELETE can rebuild every id from chunkCount alone.
 *
 * Written against the plain VectorStore interface (PgVectorStore at runtime, a mock in unit tests).
 */
@Service
@RequiredArgsConstructor
public class PackEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(PackEmbeddingService.class);

    public static final String CHUNK_LIMIT_EXCEEDED = "CHUNK_LIMIT_EXCEEDED";
    public static final String EMBEDDING_FAILED = "EMBEDDING_FAILED";
    public static final String EMPTY_DOCUMENT = "EMPTY_DOCUMENT";
    private static final String DEFAULT_FAILED_CODE = "PARSE_ERROR";

    /** Chunks per vectorStore.add call — bounds memory per call and keeps each pgvector insert
     *  batch modest, without paying per-chunk round-trip overhead. */
    static final int BATCH_SIZE = 64;

    private static final int MAX_ERROR_CODE_LENGTH = 50;
    private static final int MAX_ERROR_MESSAGE_LENGTH = 2000;
    private static final List<StudyPackStatus> EMBEDDABLE = List.of(StudyPackStatus.QUEUED, StudyPackStatus.EMBEDDING);

    private final StudyPackRepository studyPackRepository;
    private final StorageService storageService;
    private final VectorStore vectorStore;
    private final TierProperties tierProperties;
    private final ObjectMapper objectMapper;

    /** Deterministic vector id for chunk #index of a pack. A name-based (v3) UUID rather than the
     *  raw "pack:42:7" string, because PgVectorStore's id column is a UUID. */
    public static String vectorId(long packId, int index) {
        return UUID.nameUUIDFromBytes(("pack:" + packId + ":" + index).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public static List<String> vectorIds(long packId, int chunkCount) {
        return IntStream.range(0, chunkCount).mapToObj(i -> vectorId(packId, i)).toList();
    }

    public void handleParsed(DocumentParsedEvent event) {
        long packId = event.packId();
        Optional<StudyPack> found = studyPackRepository.findWithOwnerById(packId);
        if (found.isEmpty() || found.get().getStatus().isTerminal()) {
            log.info("Ignoring parsed event {} for pack {}: {}", event.eventId(), packId,
                    found.isEmpty() ? "pack no longer exists" : "already " + found.get().getStatus());
            return;
        }
        if (studyPackRepository.transition(packId, EMBEDDABLE, StudyPackStatus.EMBEDDING, now()) == 0) {
            log.info("Ignoring parsed event {} for pack {}: deleted or finished concurrently", event.eventId(), packId);
            return;
        }
        TierLimits limits = tierProperties.forTier(found.get().getOwner().getTier());
        Long ownerId = found.get().getOwner().getId();

        List<String> writtenIds = List.of();
        try {
            // Cheap pre-check on the doc-processor's own count, before reading a possibly big file.
            if (event.chunkCount() != null && event.chunkCount() > limits.maxChunksPerPack()) {
                failChunkLimit(event, event.chunkCount(), limits);
                return;
            }
            List<PackChunk> chunks = readChunks(packId, event.chunksPath());
            if (studyPackRepository.recordParseResult(packId, event.pageCount(), event.parser(),
                    event.ocrUsed(), chunks.size(), now()) == 0) {
                log.info("Pack {} was deleted or failed before embedding started", packId);
                return;
            }
            if (chunks.size() > limits.maxChunksPerPack()) {
                failChunkLimit(event, chunks.size(), limits);
                return;
            }

            List<Document> documents = toDocuments(packId, ownerId, chunks);
            if (documents.isEmpty()) {
                studyPackRepository.markFailed(packId, EMPTY_DOCUMENT,
                        "The document didn't contain any extractable text.", now());
                return;
            }
            writtenIds = vectorIds(packId, chunks.size());
            for (int from = 0; from < documents.size(); from += BATCH_SIZE) {
                vectorStore.add(documents.subList(from, Math.min(from + BATCH_SIZE, documents.size())));
            }

            if (studyPackRepository.transition(packId, List.of(StudyPackStatus.EMBEDDING), StudyPackStatus.READY, now()) == 0) {
                // Deleted (or failed) while we were embedding: its DELETE ran before these vectors
                // existed, so remove them here or they'd be orphaned.
                log.info("Pack {} was deleted during embedding; removing its {} vectors", packId, writtenIds.size());
                vectorStore.delete(writtenIds);
                return;
            }
            log.info("Pack {} READY: {} chunks embedded", packId, documents.size());
        } catch (Exception e) {
            log.error("Embedding failed for pack {} (event {})", packId, event.eventId(), e);
            deleteQuietly(packId, writtenIds);
            // Generic message on purpose: the exception text can contain storage paths/internals.
            studyPackRepository.markFailed(packId, EMBEDDING_FAILED,
                    "Could not index this document's content. Please try uploading it again.", now());
        }
    }

    public void handleFailed(DocumentFailedEvent event) {
        String code = event.errorCode() == null || event.errorCode().isBlank()
                ? DEFAULT_FAILED_CODE : truncate(event.errorCode(), MAX_ERROR_CODE_LENGTH);
        String message = event.message() == null ? null : truncate(event.message(), MAX_ERROR_MESSAGE_LENGTH);
        // Conditional update: a no-op for a deleted pack or one already READY/FAILED.
        if (studyPackRepository.markFailed(event.packId(), code, message, now()) == 0) {
            log.info("Ignoring failed event {} for pack {}: pack missing or already finished",
                    event.eventId(), event.packId());
        } else {
            log.info("Pack {} FAILED ({}) per doc-processor", event.packId(), code);
        }
    }

    private void failChunkLimit(DocumentParsedEvent event, int chunkCount, TierLimits limits) {
        studyPackRepository.recordParseResult(event.packId(), event.pageCount(), event.parser(),
                event.ocrUsed(), chunkCount, now());
        studyPackRepository.markFailed(event.packId(), CHUNK_LIMIT_EXCEEDED,
                "Document produced " + chunkCount + " chunks; your tier allows " + limits.maxChunksPerPack() + ".",
                now());
    }

    private List<PackChunk> readChunks(long packId, String chunksPath) throws IOException {
        // The path comes from another service; only ever read inside this pack's own directory.
        if (!storageService.isWithinPack(packId, chunksPath)) {
            throw new IllegalArgumentException("chunksPath outside pack " + packId + ": " + chunksPath);
        }
        try (InputStream in = storageService.open(chunksPath)) {
            List<PackChunk> chunks = objectMapper.readValue(in, new TypeReference<List<PackChunk>>() { });
            return chunks == null ? List.of() : chunks;
        }
    }

    /**
     * The vector id uses the chunk's array position (always 0..n-1) rather than its "index" field,
     * so {@link #vectorIds} can rebuild them from chunkCount even if the doc-processor ever emitted
     * gaps; the reported index is still kept as chunkIndex metadata. Metadata values are strings /
     * numbers only and nulls are skipped (Document rejects null values). There is deliberately no
     * "topic" key: handbook question selection filters on topic, so pack chunks can never leak
     * into topic interviews.
     */
    private static List<Document> toDocuments(long packId, long ownerId, List<PackChunk> chunks) {
        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            PackChunk chunk = chunks.get(i);
            if (chunk == null || chunk.text() == null || chunk.text().isBlank()) {
                continue;
            }
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("source", "pack");
            metadata.put("packId", String.valueOf(packId));
            metadata.put("ownerId", String.valueOf(ownerId));
            metadata.put("chunkIndex", chunk.index() != null ? chunk.index() : i);
            putIfNotNull(metadata, "page", chunk.page());
            putIfNotNull(metadata, "pageEnd", chunk.pageEnd());
            putIfNotNull(metadata, "section", chunk.section());
            putIfNotNull(metadata, "elementType", chunk.elementType());
            documents.add(Document.builder()
                    .id(vectorId(packId, i))
                    .text(chunk.text())
                    .metadata(metadata)
                    .build());
        }
        return documents;
    }

    private void deleteQuietly(long packId, List<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        try {
            vectorStore.delete(ids);
        } catch (RuntimeException e) {
            log.warn("Could not clean up partial vectors for pack {}: {}", packId, e.getMessage());
        }
    }

    private static void putIfNotNull(Map<String, Object> metadata, String key, Object value) {
        if (value != null) {
            metadata.put(key, value);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now();
    }
}
