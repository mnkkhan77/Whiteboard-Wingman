package com.mockinterview.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.config.StorageProperties;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.Tier;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.repository.UserRepository;
import com.mockinterview.backend.service.PackEmbeddingService;
import com.mockinterview.backend.service.StudyPackService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Full-stack tests for /api/packs through the real security chain, PostgreSQL and local-disk storage
 * (app.storage.root = target/test-uploads in the test profile). KafkaTemplate is mocked — there's
 * no broker in tests — which also lets us assert exactly what gets published and when.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StudyPackControllerTest {

    private static final byte[] PDF_BYTES = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG_BYTES = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private StudyPackRepository studyPackRepository;
    @Autowired private StudyPackService studyPackService;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private StorageProperties storageProperties;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @MockitoBean private KafkaTemplate<String, String> kafkaTemplate;
    // Spy on whichever real VectorStore bean is configured, so delete() can be verified.
    @MockitoSpyBean private VectorStore vectorStore;

    private record Registered(String token, User user) {}

    @BeforeEach
    void stubKafka() {
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
    }

    private Registered register() throws Exception {
        return register(Tier.FREE);
    }

    private Registered register(Tier tier) throws Exception {
        String email = "packs-" + UUID.randomUUID() + "@example.com";
        String body = objectMapper.writeValueAsString(Map.of(
                "email", email, "password", "password123", "displayName", "Pack User"));
        String response = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        User user = userRepository.findByEmail(email).orElseThrow();
        if (tier != Tier.FREE) {
            user.setTier(tier);
            user = userRepository.save(user);
        }
        return new Registered(objectMapper.readTree(response).get("token").asText(), user);
    }

    private String guestToken() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("guestId", UUID.randomUUID().toString()));
        String response = mockMvc.perform(post("/api/auth/guest").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("token").asText();
    }

    private static MockMultipartHttpServletRequestBuilder upload(String token, String fileName, byte[] content) {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/packs")
                .file(new MockMultipartFile("file", fileName, "application/octet-stream", content));
        builder.header("Authorization", "Bearer " + token);
        return builder;
    }

    private JsonNode uploadOk(String token, String fileName) throws Exception {
        String response = mockMvc.perform(upload(token, fileName, PDF_BYTES))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response);
    }

    private Path storagePath(String relative) {
        return Paths.get(storageProperties.root()).toAbsolutePath().normalize().resolve(relative);
    }

    /** JDBC statements Hibernate prepared while serving one request (statistics are toggled on only
     *  for its duration, since the Spring context is shared with other test classes). */
    private long statementsFor(RequestBuilder request) throws Exception {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            mockMvc.perform(request).andExpect(status().isOk());
            return statistics.getPrepareStatementCount();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
    }

    @Test
    void uploadStoresTheFileCreatesAQueuedPackAndPublishesTheUploadedEvent() throws Exception {
        Registered owner = register();

        JsonNode pack = uploadOk(owner.token(), "Chapter 2 Notes.pdf");

        long packId = pack.get("id").asLong();
        assertThat(pack.get("title").asText()).isEqualTo("Chapter 2 Notes");
        assertThat(pack.get("fileName").asText()).isEqualTo("Chapter 2 Notes.pdf");
        assertThat(pack.get("status").asText()).isEqualTo("QUEUED");
        assertThat(pack.get("sizeBytes").asLong()).isEqualTo(PDF_BYTES.length);
        assertThat(pack.has("storagePath")).isFalse();

        StudyPack saved = studyPackRepository.findById(packId).orElseThrow();
        assertThat(saved.getStoragePath()).isEqualTo("packs/" + packId + "/source.pdf");
        assertThat(saved.getContentType()).isEqualTo("application/pdf");
        assertThat(Files.readAllBytes(storagePath(saved.getStoragePath()))).isEqualTo(PDF_BYTES);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq("wingman.document.uploaded"), eq(String.valueOf(packId)), payload.capture());
        JsonNode event = objectMapper.readTree(payload.getValue());
        assertThat(event.get("packId").asLong()).isEqualTo(packId);
        assertThat(event.get("ownerId").asLong()).isEqualTo(owner.user().getId());
        assertThat(event.get("tier").asText()).isEqualTo("FREE");
        assertThat(event.get("fileName").asText()).isEqualTo("Chapter 2 Notes.pdf");
        assertThat(event.get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(event.get("storagePath").asText()).isEqualTo("packs/" + packId + "/source.pdf");
        assertThat(event.get("sizeBytes").asLong()).isEqualTo(PDF_BYTES.length);
        assertThat(event.get("ocrEnabled").asBoolean()).isFalse();
        assertThat(event.get("maxPages").asInt()).isEqualTo(50);
        assertThat(event.get("eventId").asText()).isNotBlank();
        assertThat(event.get("occurredAt").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
    }

    @Test
    void anExplicitTitleOverridesTheFileName() throws Exception {
        Registered owner = register();
        mockMvc.perform(upload(owner.token(), "notes.pdf", PDF_BYTES).param("title", "  Distributed Systems  "))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Distributed Systems"));
    }

    @Test
    void theUploadedEventIsOnlyPublishedAfterTheTransactionCommits() throws Exception {
        Registered owner = register();
        MockMultipartFile file = new MockMultipartFile("file", "rollback.pdf", "application/pdf", PDF_BYTES);

        // Upload inside an outer transaction that then fails: nothing may be published, and the
        // file already written to disk must be cleaned up again.
        Long[] packId = new Long[1];
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            packId[0] = studyPackService.upload(owner.user(), file, null).id();
            verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString()); // not before commit
            throw new IllegalStateException("simulated failure after upload");
        })).isInstanceOf(IllegalStateException.class);

        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
        assertThat(studyPackRepository.findById(packId[0])).isEmpty();
        assertThat(storagePath("packs/" + packId[0])).doesNotExist();
    }

    @Test
    void guestsCannotUpload() throws Exception {
        mockMvc.perform(upload(guestToken(), "notes.pdf", PDF_BYTES))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GUEST_UPLOAD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
        verify(kafkaTemplate, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void uploadIsRejectedOnceTheTierPackLimitIsReached() throws Exception {
        Registered owner = register(); // FREE: 3 packs
        for (int i = 0; i < 3; i++) {
            uploadOk(owner.token(), "notes" + i + ".pdf");
        }
        mockMvc.perform(upload(owner.token(), "one-too-many.pdf", PDF_BYTES))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PACK_LIMIT_REACHED"));
    }

    @Test
    void uploadIsRejectedWhenOverTheTierFileSize() throws Exception {
        Registered owner = register(); // FREE: 10 MB
        byte[] big = new byte[10 * 1024 * 1024 + 1];
        System.arraycopy(PDF_BYTES, 0, big, 0, PDF_BYTES.length);
        mockMvc.perform(upload(owner.token(), "big.pdf", big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void uploadIsRejectedForAFormatTheTierDoesNotAllow() throws Exception {
        Registered owner = register(); // FREE: pdf only
        mockMvc.perform(upload(owner.token(), "slides.pptx", new byte[]{0x50, 0x4B, 0x03, 0x04, 0}))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FORMAT"));
    }

    @Test
    void aHigherTierUnlocksMoreFormats() throws Exception {
        Registered owner = register(Tier.MAX);
        mockMvc.perform(upload(owner.token(), "diagram.png", PNG_BYTES))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("QUEUED"));
    }

    @Test
    void aRenamedFileIsRejectedByItsMagicBytes() throws Exception {
        Registered owner = register();
        mockMvc.perform(upload(owner.token(), "not-really.pdf", PNG_BYTES))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_FORMAT"));
    }

    @Test
    void anEmptyFileIsRejected() throws Exception {
        Registered owner = register();
        mockMvc.perform(upload(owner.token(), "empty.pdf", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMPTY_FILE"));
    }

    @Test
    void aMissingFilePartIsABadRequest() throws Exception {
        Registered owner = register();
        mockMvc.perform(multipart("/api/packs").param("title", "x").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitsReflectTheTierAndCurrentUsage() throws Exception {
        Registered owner = register();
        uploadOk(owner.token(), "notes.pdf");

        mockMvc.perform(get("/api/packs/limits").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.maxFileBytes").value(10485760))
                .andExpect(jsonPath("$.maxPages").value(50))
                .andExpect(jsonPath("$.maxPacks").value(3))
                .andExpect(jsonPath("$.maxChunksPerPack").value(300))
                .andExpect(jsonPath("$.ocrEnabled").value(false))
                .andExpect(jsonPath("$.allowedExtensions[0]").value("pdf"))
                .andExpect(jsonPath("$.packsUsed").value(1));

        Registered max = register(Tier.MAX);
        mockMvc.perform(get("/api/packs/limits").header("Authorization", "Bearer " + max.token()))
                .andExpect(jsonPath("$.maxPacks").value(-1))
                .andExpect(jsonPath("$.allowedExtensions.length()").value(6));
    }

    @Test
    void listReturnsOnlyTheCallersPacksNewestFirst() throws Exception {
        Registered owner = register();
        Registered other = register();
        long first = uploadOk(owner.token(), "first.pdf").get("id").asLong();
        long second = uploadOk(owner.token(), "second.pdf").get("id").asLong();
        uploadOk(other.token(), "someone-elses.pdf");

        mockMvc.perform(get("/api/packs").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first));
    }

    @Test
    void listingPacksCostsAFixedNumberOfStatementsHoweverManyPacksThereAre() throws Exception {
        Registered owner = register(Tier.MAX); // unlimited packs
        uploadOk(owner.token(), "one.pdf");
        long withOnePack = statementsFor(get("/api/packs").header("Authorization", "Bearer " + owner.token()));
        for (int i = 0; i < 4; i++) {
            uploadOk(owner.token(), "more" + i + ".pdf");
        }
        long withFivePacks = statementsFor(get("/api/packs").header("Authorization", "Bearer " + owner.token()));

        // No per-pack select (StudyPack.owner is LAZY and PackDto never touches it). The 4 are the
        // JWT filter's revoked-token check and user lookup, the controller's user lookup, and the
        // list query itself.
        assertThat(withFivePacks).isEqualTo(withOnePack).isEqualTo(4);
    }

    @Test
    void getIsOwnerOnlyAnd404sForEveryoneElse() throws Exception {
        Registered owner = register();
        Registered other = register();
        long packId = uploadOk(owner.token(), "mine.pdf").get("id").asLong();

        mockMvc.perform(get("/api/packs/" + packId).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("mine.pdf"));
        mockMvc.perform(get("/api/packs/" + packId).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/packs/999999").header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteRemovesThePackItsFilesAndItsVectors() throws Exception {
        Registered owner = register();
        long packId = uploadOk(owner.token(), "doomed.pdf").get("id").asLong();
        // Simulate a finished pipeline: chunks file on disk and a known chunk count.
        Files.writeString(storagePath("packs/" + packId + "/chunks.json"), "[]");
        StudyPack pack = studyPackRepository.findById(packId).orElseThrow();
        pack.setStatus(StudyPackStatus.READY);
        pack.setChunkCount(3);
        studyPackRepository.save(pack);

        mockMvc.perform(delete("/api/packs/" + packId).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNoContent());

        assertThat(studyPackRepository.findById(packId)).isEmpty();
        assertThat(storagePath("packs/" + packId)).doesNotExist();
        mockMvc.perform(get("/api/packs/" + packId).header("Authorization", "Bearer " + owner.token()))
                .andExpect(status().isNotFound());
        // Vector ids are rebuilt from chunkCount alone.
        verify(vectorStore).delete(PackEmbeddingService.vectorIds(packId, 3));
    }

    @Test
    void deleteIsOwnerOnly() throws Exception {
        Registered owner = register();
        Registered other = register();
        long packId = uploadOk(owner.token(), "keep.pdf").get("id").asLong();

        mockMvc.perform(delete("/api/packs/" + packId).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        assertThat(studyPackRepository.findById(packId)).isPresent();
    }

    @Test
    void everyPackEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/packs")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/packs/limits")).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/packs/1")).andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/packs/1")).andExpect(status().isForbidden());
        mockMvc.perform(multipart("/api/packs").file(new MockMultipartFile("file", "a.pdf", "application/pdf", PDF_BYTES)))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestsCanStillSeeTheirLimitsAndEmptyList() throws Exception {
        String token = guestToken();
        mockMvc.perform(get("/api/packs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/packs/limits").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.packsUsed").value(0));
    }
}
