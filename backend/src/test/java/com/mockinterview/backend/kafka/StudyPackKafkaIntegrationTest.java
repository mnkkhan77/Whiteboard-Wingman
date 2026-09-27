package com.mockinterview.backend.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.config.StorageProperties;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.StudyPackRepository;
import com.mockinterview.backend.repository.UserRepository;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * End-to-end over a real (embedded) broker: proves the wiring unit tests can't — Boot's listener
 * factory picks up KafkaConfig's error handler, String JSON values decode into the event records,
 * a parsed event drives a pack to READY, and an undecodable message lands on {topic}.DLT
 * without blocking the partition. The VectorStore is a spy with add() stubbed out, so this
 * doesn't depend on embedding speed.
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
})
@EmbeddedKafka(partitions = 1)
@ActiveProfiles("test")
class StudyPackKafkaIntegrationTest {

    @Autowired private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired private EmbeddedKafkaBroker broker;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private StudyPackRepository studyPackRepository;
    @Autowired private StorageProperties storageProperties;
    @Autowired private DocumentEventPublisher documentEventPublisher;

    @MockitoSpyBean private VectorStore vectorStore;

    private StudyPack queuedPack() {
        User user = new User();
        user.setEmail("kafka-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("x");
        user = userRepository.save(user);

        StudyPack pack = new StudyPack();
        pack.setOwner(user);
        pack.setTitle("notes");
        pack.setFileName("notes.pdf");
        pack.setContentType("application/pdf");
        pack.setExtension("pdf");
        pack.setSizeBytes(10);
        pack.setStatus(StudyPackStatus.QUEUED);
        return studyPackRepository.save(pack);
    }

    private StudyPackStatus awaitTerminalStatus(long packId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 60_000;
        while (System.currentTimeMillis() < deadline) {
            StudyPackStatus status = studyPackRepository.findById(packId).orElseThrow().getStatus();
            if (status.isTerminal()) {
                return status;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Pack " + packId + " never reached READY/FAILED");
    }

    private Consumer<String, String> consumerFor(String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps("it-" + UUID.randomUUID(), "false", broker);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props,
                new StringDeserializer(), new StringDeserializer()).createConsumer();
        consumer.subscribe(List.of(topic));
        return consumer;
    }

    @Test
    void aParsedEventOnTheBrokerEmbedsThePackAndMarksItReady() throws Exception {
        doNothing().when(vectorStore).add(anyList());
        StudyPack pack = queuedPack();
        Path chunks = Paths.get(storageProperties.root()).toAbsolutePath().resolve("packs/" + pack.getId() + "/chunks.json");
        Files.createDirectories(chunks.getParent());
        objectMapper.writeValue(chunks.toFile(), List.of(
                new PackChunk(0, "ACID stands for atomicity, consistency, isolation, durability.", 1, 1, "Transactions", "text"),
                new PackChunk(1, "| level | anomaly |\n|---|---|\n| READ COMMITTED | non-repeatable read |", 2, 2, null, "table")));

        String event = """
                {"eventId":"%s","packId":%d,"pageCount":2,"parser":"docling","ocrUsed":false,
                 "chunkCount":2,"chunksPath":"packs/%d/chunks.json","occurredAt":"2026-09-27T10:16:02Z"}
                """.formatted(UUID.randomUUID(), pack.getId(), pack.getId());
        kafkaTemplate.send("wingman.document.parsed", String.valueOf(pack.getId()), event).get();

        assertThat(awaitTerminalStatus(pack.getId())).isEqualTo(StudyPackStatus.READY);
        StudyPack ready = studyPackRepository.findById(pack.getId()).orElseThrow();
        assertThat(ready.getChunkCount()).isEqualTo(2);
        assertThat(ready.getPageCount()).isEqualTo(2);
        assertThat(ready.getParser()).isEqualTo("docling");
        verify(vectorStore, atLeastOnce()).add(anyList());
    }

    @Test
    void aFailedEventOnTheBrokerMarksThePackFailed() throws Exception {
        StudyPack pack = queuedPack();
        String event = """
                {"eventId":"%s","packId":%d,"errorCode":"PAGE_LIMIT_EXCEEDED",
                 "message":"Document has 120 pages; your tier allows 50.","occurredAt":"2026-09-27T10:16:02Z"}
                """.formatted(UUID.randomUUID(), pack.getId());
        kafkaTemplate.send("wingman.document.failed", String.valueOf(pack.getId()), event).get();

        assertThat(awaitTerminalStatus(pack.getId())).isEqualTo(StudyPackStatus.FAILED);
        assertThat(studyPackRepository.findById(pack.getId()).orElseThrow().getErrorCode()).isEqualTo("PAGE_LIMIT_EXCEEDED");
    }

    @Test
    void anUndecodableMessageGoesToTheDeadLetterTopic() throws Exception {
        try (Consumer<String, String> dlt = consumerFor("wingman.document.parsed.DLT")) {
            kafkaTemplate.send("wingman.document.parsed", "poison", "{this is not json").get();

            ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(dlt, "wingman.document.parsed.DLT",
                    Duration.ofSeconds(60));
            assertThat(dead.value()).isEqualTo("{this is not json");
            assertThat(dead.key()).isEqualTo("poison");
        }
    }

    @Test
    void thePublisherWritesContractJsonKeyedByPackIdWithoutJavaTypeHeaders() throws Exception {
        try (Consumer<String, String> uploaded = consumerFor("wingman.document.uploaded")) {
            DocumentUploadedEvent event = new DocumentUploadedEvent("e1", 99L, 7L,
                    com.mockinterview.backend.entity.Tier.PRO, "notes.docx", "application/x", "packs/99/source.docx",
                    123, true, 300, "2026-09-27T10:15:30Z");
            documentEventPublisher.publishUploaded(event);

            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(uploaded, "wingman.document.uploaded",
                    Duration.ofSeconds(30));
            JsonNode json = objectMapper.readTree(record.value());
            assertThat(json.get("tier").asText()).isEqualTo("PRO");
            assertThat(json.get("occurredAt").asText()).isEqualTo("2026-09-27T10:15:30Z");
            assertThat(record.key()).isEqualTo("99");
            assertThat(record.headers().lastHeader("__TypeId__")).isNull();
        }
    }
}
