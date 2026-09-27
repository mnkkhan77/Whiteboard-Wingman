package com.mockinterview.backend.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mockinterview.backend.config.StudyPackKafkaProperties;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes wingman.document.uploaded. Values are serialized here with the app's ObjectMapper and
 * sent as plain String JSON (StringSerializer), rather than via spring-kafka's JsonSerializer, so
 * the wire format is exactly the contract's JSON — no __TypeId__ headers naming Java classes that
 * the Python doc-processor would have to ignore. Key = packId, so all events for one pack land
 * on the same partition, in order.
 *
 * Only ever called after the upload's DB transaction has committed (see
 * StudyPackLifecycleListener), so the doc-processor can never race ahead of the pack row.
 */
@Component
@RequiredArgsConstructor
public class DocumentEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final StudyPackKafkaProperties kafkaProperties;

    public void publishUploaded(DocumentUploadedEvent event) {
        String topic = kafkaProperties.topics().uploaded();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize upload event for pack " + event.packId(), e);
        }
        // Async send: the HTTP response doesn't wait for the broker ack. A failed send leaves the
        // pack QUEUED (logged here); the user can delete and re-upload. A transactional outbox
        // would close that gap, at the cost of a poller — not worth it at this scale.
        // Also catches a synchronous failure (e.g. broker metadata timeout, bounded by the
        // producer's max.block.ms): the pack row is already committed, so failing the HTTP request
        // now would only hide a pack the user can see and delete.
        try {
            kafkaTemplate.send(topic, String.valueOf(event.packId()), payload)
                    .whenComplete((result, ex) -> {
                        if (ex != null) {
                            log.error("Failed to publish {} for pack {}", topic, event.packId(), ex);
                        } else {
                            log.info("Published {} for pack {} (eventId={})", topic, event.packId(), event.eventId());
                        }
                    });
        } catch (RuntimeException e) {
            log.error("Failed to publish {} for pack {}", topic, event.packId(), e);
        }
    }
}
