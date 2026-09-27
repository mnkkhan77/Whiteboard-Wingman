package com.mockinterview.backend.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.service.PackEmbeddingService;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes the doc-processor's results (consumer group from spring.kafka.consumer.group-id,
 * wingman-backend). Values arrive as String and are decoded here with the app's ObjectMapper — a
 * value that isn't valid event JSON throws a non-retryable exception, so KafkaConfig's error
 * handler sends it straight to {topic}.DLT instead of retrying a message that can never succeed.
 *
 * Threading: embedding runs synchronously on the listener thread, on purpose. The offset is only
 * committed after the handler returns (ack-mode RECORD), so a crash mid-embedding means the event
 * is redelivered and the (idempotent) handler finishes the job — handing the work to another
 * executor would commit the offset first and could strand a pack in EMBEDDING forever. The cost
 * is that one slow document holds the partition, which is why application.yml sets
 * max.poll.records=1 and a generous max.poll.interval.ms (30 min, far above a 6000-chunk MAX pack
 * on CPU) so the broker doesn't consider the consumer dead and rebalance mid-embedding. Embedding
 * is CPU-bound anyway, so running packs one at a time per instance costs little throughput.
 */
@Component
@RequiredArgsConstructor
public class DocumentEventsListener {

    private final ObjectMapper objectMapper;
    private final PackEmbeddingService packEmbeddingService;

    @KafkaListener(topics = "${app.kafka.topics.parsed}")
    public void onParsed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        DocumentParsedEvent event = decode(record, DocumentParsedEvent.class);
        requirePackId(event.packId(), record);
        packEmbeddingService.handleParsed(event);
    }

    @KafkaListener(topics = "${app.kafka.topics.failed}")
    public void onFailed(ConsumerRecord<String, String> record) throws JsonProcessingException {
        DocumentFailedEvent event = decode(record, DocumentFailedEvent.class);
        requirePackId(event.packId(), record);
        packEmbeddingService.handleFailed(event);
    }

    private <T> T decode(ConsumerRecord<String, String> record, Class<T> type) throws JsonProcessingException {
        if (record.value() == null || record.value().isBlank()) {
            throw new InvalidDocumentEventException(
                    "Empty event on " + record.topic() + " at offset " + record.offset());
        }
        return objectMapper.readValue(record.value(), type);
    }

    private static void requirePackId(Long packId, ConsumerRecord<String, String> record) {
        if (packId == null) {
            throw new InvalidDocumentEventException(
                    "Event on " + record.topic() + " at offset " + record.offset() + " has no packId");
        }
    }
}
