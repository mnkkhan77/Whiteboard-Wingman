package com.mockinterview.backend.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.config.KafkaConfig;
import com.mockinterview.backend.service.PackEmbeddingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Decoding side of the consumers: contract JSON from the Python doc-processor (unknown extra
 * fields tolerated, as with Boot's ObjectMapper) maps onto the event records, and anything
 * undecodable fails with an exception type KafkaConfig marks non-retryable (straight to the DLT).
 */
class DocumentEventsListenerTest {

    private final PackEmbeddingService packEmbeddingService = mock(PackEmbeddingService.class);
    private final DocumentEventsListener listener = new DocumentEventsListener(
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false),
            packEmbeddingService);

    private static ConsumerRecord<String, String> record(String topic, String value) {
        return new ConsumerRecord<>(topic, 0, 0L, "42", value);
    }

    @Test
    void decodesAContractParsedEventAndDelegates() throws Exception {
        listener.onParsed(record("wingman.document.parsed", """
                {"eventId":"e1","packId":42,"pageCount":37,"parser":"docling","ocrUsed":false,
                 "chunkCount":180,"chunksPath":"packs/42/chunks.json","occurredAt":"2026-09-27T10:16:02+00:00",
                 "someFutureField":true}
                """));

        ArgumentCaptor<DocumentParsedEvent> captor = ArgumentCaptor.forClass(DocumentParsedEvent.class);
        verify(packEmbeddingService).handleParsed(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new DocumentParsedEvent("e1", 42L, 37, "docling", false, 180,
                "packs/42/chunks.json", "2026-09-27T10:16:02+00:00"));
    }

    @Test
    void decodesAContractFailedEventAndDelegates() throws Exception {
        listener.onFailed(record("wingman.document.failed", """
                {"eventId":"e2","packId":42,"errorCode":"OCR_REQUIRED","message":"No text layer","occurredAt":"2026-09-27T10:16:02Z"}
                """));

        verify(packEmbeddingService).handleFailed(
                new DocumentFailedEvent("e2", 42L, "OCR_REQUIRED", "No text layer", "2026-09-27T10:16:02Z"));
    }

    @Test
    void malformedJsonThrowsANonRetryableDecodeError() {
        assertThatThrownBy(() -> listener.onParsed(record("wingman.document.parsed", "{not json")))
                .isInstanceOf(JsonProcessingException.class);
        verifyNoInteractions(packEmbeddingService);
    }

    @Test
    void eventsWithoutAPackIdOrWithoutAValueAreRejected() {
        assertThatThrownBy(() -> listener.onFailed(record("wingman.document.failed", "{\"errorCode\":\"PARSE_ERROR\"}")))
                .isInstanceOf(InvalidDocumentEventException.class);
        assertThatThrownBy(() -> listener.onParsed(record("wingman.document.parsed", null)))
                .isInstanceOf(InvalidDocumentEventException.class);
        verifyNoInteractions(packEmbeddingService);
    }

    @Test
    void decodeFailuresAreConfiguredAsNotRetryable() {
        var handler = new KafkaConfig().kafkaErrorHandler(mock(org.springframework.kafka.core.KafkaTemplate.class));
        // removeClassification returns the previous classification: false = not retryable.
        assertThat(handler.removeClassification(JsonProcessingException.class)).isFalse();
        assertThat(handler.removeClassification(InvalidDocumentEventException.class)).isFalse();
    }
}
