package com.mockinterview.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.config.StorageProperties;
import com.mockinterview.backend.config.TierProperties;
import com.mockinterview.backend.config.TierProperties.TierLimits;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.Tier;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.kafka.DocumentFailedEvent;
import com.mockinterview.backend.kafka.DocumentParsedEvent;
import com.mockinterview.backend.kafka.PackChunk;
import com.mockinterview.backend.repository.StudyPackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The parsed/failed handlers behind the Kafka consumers, with a mocked repository and VectorStore
 * and a real StorageService over a temp dir. Focus: at-least-once idempotency (deleted / already
 * finished packs are ignored), the tier chunk cap, and embedding failures ending in FAILED.
 */
class PackEmbeddingServiceTest {

    private static final long PACK_ID = 42L;
    private static final long OWNER_ID = 7L;
    private static final String CHUNKS_PATH = "packs/42/chunks.json";

    @TempDir Path storageRoot;

    private StudyPackRepository repository;
    private VectorStore vectorStore;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private PackEmbeddingService service;

    @BeforeEach
    void setUp() {
        repository = mock(StudyPackRepository.class);
        vectorStore = mock(VectorStore.class);
        TierLimits free = new TierLimits(10_485_760, 50, 3, 300, false, List.of("pdf"), 20_000);
        TierLimits pro = new TierLimits(52_428_800, 300, 20, 2000, true, List.of("pdf", "docx"), 500_000);
        TierLimits max = new TierLimits(209_715_200, 1000, -1, 6000, true, List.of("pdf"), 2_000_000);
        StorageService storage = new StorageService(new StorageProperties(storageRoot.toString()));
        service = new PackEmbeddingService(repository, storage, vectorStore,
                new TierProperties(free, pro, max), objectMapper);

        lenient().when(repository.transition(anyLong(), anyCollection(), any(), any())).thenReturn(1);
        lenient().when(repository.recordParseResult(anyLong(), any(), any(), any(), any(), any())).thenReturn(1);
        lenient().when(repository.markFailed(anyLong(), any(), any(), any())).thenReturn(1);
    }

    private void givenPack(StudyPackStatus status, Tier tier) {
        User owner = new User();
        owner.setId(OWNER_ID);
        owner.setTier(tier);
        StudyPack pack = new StudyPack();
        pack.setId(PACK_ID);
        pack.setOwner(owner);
        pack.setStatus(status);
        when(repository.findWithOwnerById(PACK_ID)).thenReturn(Optional.of(pack));
    }

    private void givenChunksFile(List<PackChunk> chunks) throws Exception {
        Path file = storageRoot.resolve(CHUNKS_PATH);
        Files.createDirectories(file.getParent());
        objectMapper.writeValue(file.toFile(), chunks);
    }

    private static List<PackChunk> chunks(int n) {
        List<PackChunk> chunks = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            chunks.add(new PackChunk(i, "chunk text " + i, i + 1, i % 2 == 0 ? null : i + 2,
                    i % 3 == 0 ? null : "Chapter 1 > Part " + i, "text"));
        }
        return chunks;
    }

    private static DocumentParsedEvent parsed(Integer chunkCount) {
        return parsed(chunkCount, CHUNKS_PATH);
    }

    private static DocumentParsedEvent parsed(Integer chunkCount, String chunksPath) {
        return new DocumentParsedEvent(UUID.randomUUID().toString(), PACK_ID, 37, "docling", false,
                chunkCount, chunksPath, "2026-09-27T10:16:02Z");
    }

    @SuppressWarnings("unchecked")
    private List<Document> allAddedDocuments() {
        ArgumentCaptor<List<Document>> captor = ArgumentCaptor.forClass(List.class);
        verify(vectorStore, atLeastOnce()).add(captor.capture());
        return captor.getAllValues().stream().flatMap(List::stream).toList();
    }

    @Test
    void embedsChunksInBatchesWithPackMetadataAndMarksThePackReady() throws Exception {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);
        List<PackChunk> chunks = new ArrayList<>(chunks(130));
        chunks.set(5, new PackChunk(5, "   ", 6, null, null, "text")); // blank: skipped, id slot kept
        givenChunksFile(chunks);

        service.handleParsed(parsed(130));

        verify(repository).transition(eq(PACK_ID), anyCollection(), eq(StudyPackStatus.EMBEDDING), any());
        verify(repository).recordParseResult(eq(PACK_ID), eq(37), eq("docling"), eq(false), eq(130), any());
        verify(vectorStore, times(3)).add(anyList()); // 129 docs -> 64 + 64 + 1
        verify(repository).transition(eq(PACK_ID), eq(List.of(StudyPackStatus.EMBEDDING)), eq(StudyPackStatus.READY), any());
        verify(repository, never()).markFailed(anyLong(), any(), any(), any());

        List<Document> docs = allAddedDocuments();
        assertThat(docs).hasSize(129);
        Document first = docs.get(0);
        assertThat(first.getId()).isEqualTo(PackEmbeddingService.vectorId(PACK_ID, 0));
        assertThat(first.getText()).isEqualTo("chunk text 0");
        assertThat(first.getMetadata())
                .containsEntry("source", "pack")
                .containsEntry("packId", "42")
                .containsEntry("ownerId", "7")
                .containsEntry("chunkIndex", 0)
                .containsEntry("page", 1)
                .containsEntry("elementType", "text")
                .doesNotContainKeys("topic", "pageEnd", "section"); // nulls skipped, never a topic
        assertThat(docs.get(1).getMetadata()).containsEntry("pageEnd", 3).containsEntry("section", "Chapter 1 > Part 1");
        assertThat(docs).extracting(Document::getId).doesNotContain(PackEmbeddingService.vectorId(PACK_ID, 5));
    }

    @Test
    void vectorIdsAreDeterministicUuidsSoRedeliveryOverwritesAndDeleteCanRebuildThem() {
        String id = PackEmbeddingService.vectorId(PACK_ID, 3);
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(id).isEqualTo(PackEmbeddingService.vectorId(PACK_ID, 3));
        assertThat(id).isNotEqualTo(PackEmbeddingService.vectorId(43L, 3));
        assertThat(id).isEqualTo(UUID.nameUUIDFromBytes("pack:42:3".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
        assertThat(PackEmbeddingService.vectorIds(PACK_ID, 4)).hasSize(4).endsWith(id);
    }

    @Test
    void ignoresAParsedEventForADeletedPack() {
        when(repository.findWithOwnerById(PACK_ID)).thenReturn(Optional.empty());

        service.handleParsed(parsed(10));

        verifyNoInteractions(vectorStore);
        verify(repository, never()).transition(anyLong(), anyCollection(), any(), any());
        verify(repository, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void ignoresARedeliveredParsedEventForAPackAlreadyReadyOrFailed() {
        for (StudyPackStatus terminal : List.of(StudyPackStatus.READY, StudyPackStatus.FAILED)) {
            givenPack(terminal, Tier.FREE);
            service.handleParsed(parsed(10));
        }
        verifyNoInteractions(vectorStore);
        verify(repository, never()).transition(anyLong(), anyCollection(), any(), any());
        verify(repository, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void ignoresTheEventWhenThePackFinishesOrIsDeletedBetweenReadAndTransition() {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);
        when(repository.transition(eq(PACK_ID), anyCollection(), eq(StudyPackStatus.EMBEDDING), any())).thenReturn(0);

        service.handleParsed(parsed(10));

        verifyNoInteractions(vectorStore);
        verify(repository, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void reprocessesAPackStillEmbeddingAfterACrash() throws Exception {
        givenPack(StudyPackStatus.EMBEDDING, Tier.FREE);
        givenChunksFile(chunks(3));

        service.handleParsed(parsed(3));

        assertThat(allAddedDocuments()).extracting(Document::getId)
                .containsExactlyElementsOf(PackEmbeddingService.vectorIds(PACK_ID, 3));
        verify(repository).transition(eq(PACK_ID), eq(List.of(StudyPackStatus.EMBEDDING)), eq(StudyPackStatus.READY), any());
    }

    @Test
    void failsWithChunkLimitExceededFromTheEventCountWithoutReadingTheFile() {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE); // FREE cap = 300; no chunks file exists

        service.handleParsed(parsed(301));

        verify(repository).recordParseResult(eq(PACK_ID), eq(37), eq("docling"), eq(false), eq(301), any());
        verify(repository).markFailed(eq(PACK_ID), eq("CHUNK_LIMIT_EXCEEDED"), contains("300"), any());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void failsWithChunkLimitExceededFromTheActualFileCount() throws Exception {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);
        givenChunksFile(chunks(301));

        service.handleParsed(parsed(null)); // doc-processor didn't report a count

        verify(repository).markFailed(eq(PACK_ID), eq("CHUNK_LIMIT_EXCEEDED"), any(), any());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void usesTheOwnersTierForTheChunkCap() throws Exception {
        givenPack(StudyPackStatus.QUEUED, Tier.PRO); // PRO cap = 2000
        givenChunksFile(chunks(301));

        service.handleParsed(parsed(301));

        verify(repository, never()).markFailed(anyLong(), any(), any(), any());
        verify(repository).transition(eq(PACK_ID), eq(List.of(StudyPackStatus.EMBEDDING)), eq(StudyPackStatus.READY), any());
    }

    @Test
    void anEmbeddingExceptionMarksThePackFailedAndCleansUpPartialVectors() throws Exception {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);
        givenChunksFile(chunks(100));
        doNothing().doThrow(new RuntimeException("onnx runtime exploded")).when(vectorStore).add(anyList());

        service.handleParsed(parsed(100));

        verify(repository).markFailed(eq(PACK_ID), eq("EMBEDDING_FAILED"), argThat(m -> !m.contains("onnx")), any());
        verify(vectorStore).delete(PackEmbeddingService.vectorIds(PACK_ID, 100));
        verify(repository, never()).transition(eq(PACK_ID), anyCollection(), eq(StudyPackStatus.READY), any());
    }

    @Test
    void aMissingChunksFileMarksThePackFailed() {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);

        service.handleParsed(parsed(10));

        verify(repository).markFailed(eq(PACK_ID), eq("EMBEDDING_FAILED"), any(), any());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void refusesAChunksPathOutsideThePacksOwnDirectory() throws Exception {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);
        Path other = storageRoot.resolve("packs/43/chunks.json");
        Files.createDirectories(other.getParent());
        objectMapper.writeValue(other.toFile(), chunks(2));

        service.handleParsed(parsed(2, "packs/43/chunks.json"));
        service.handleParsed(parsed(2, "packs/42/../43/chunks.json"));

        verify(repository, times(2)).markFailed(eq(PACK_ID), eq("EMBEDDING_FAILED"), any(), any());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void anEmptyDocumentIsMarkedFailed() throws Exception {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);
        givenChunksFile(List.of());

        service.handleParsed(parsed(0));

        verify(repository).markFailed(eq(PACK_ID), eq("EMPTY_DOCUMENT"), any(), any());
        verifyNoInteractions(vectorStore);
    }

    @Test
    void removesItsVectorsWhenThePackIsDeletedWhileEmbedding() throws Exception {
        givenPack(StudyPackStatus.QUEUED, Tier.FREE);
        givenChunksFile(chunks(5));
        when(repository.transition(eq(PACK_ID), eq(List.of(StudyPackStatus.EMBEDDING)), eq(StudyPackStatus.READY), any()))
                .thenReturn(0);

        service.handleParsed(parsed(5));

        verify(vectorStore).delete(PackEmbeddingService.vectorIds(PACK_ID, 5));
        verify(repository, never()).markFailed(anyLong(), any(), any(), any());
    }

    @Test
    void aFailedEventMarksThePackFailedWithTheDocProcessorsCodeAndMessage() {
        service.handleFailed(new DocumentFailedEvent("e1", PACK_ID, "PAGE_LIMIT_EXCEEDED",
                "Document has 120 pages; your tier allows 50.", "2026-09-27T10:16:02Z"));

        verify(repository).markFailed(eq(PACK_ID), eq("PAGE_LIMIT_EXCEEDED"),
                eq("Document has 120 pages; your tier allows 50."), any());
    }

    @Test
    void aFailedEventWithoutACodeDefaultsToParseErrorAndLongMessagesAreTruncated() {
        service.handleFailed(new DocumentFailedEvent("e1", PACK_ID, " ", "x".repeat(5000), null));

        verify(repository).markFailed(eq(PACK_ID), eq("PARSE_ERROR"), argThat(m -> m.length() == 2000), any());
    }

    @Test
    void aFailedEventForADeletedOrFinishedPackIsANoOp() {
        when(repository.markFailed(anyLong(), any(), any(), any())).thenReturn(0);

        service.handleFailed(new DocumentFailedEvent("e1", PACK_ID, "PARSE_ERROR", "boom", null));

        verify(repository).markFailed(eq(PACK_ID), eq("PARSE_ERROR"), eq("boom"), any());
        verifyNoInteractions(vectorStore);
    }
}
