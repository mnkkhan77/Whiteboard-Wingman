package com.mockinterview.backend.kafka;

import com.mockinterview.backend.config.OutboxProperties;
import com.mockinterview.backend.entity.OutboxEvent;
import com.mockinterview.backend.entity.OutboxStatus;
import com.mockinterview.backend.repository.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends pending outbox rows (V23, OutboxEvent) to Kafka and deletes them once the broker confirms
 * receipt — the other half of the transactional outbox pattern (see the migration's comment for
 * why: a row written in the same transaction as the business change it describes can never be
 * silently lost, unlike the previous fire-and-forget async send that a broker hiccup could drop).
 *
 * @Scheduled(fixedDelayString): the next poll starts {@code pollIntervalMs} after the previous one
 * finished, so a slow or unreachable broker never causes overlapping polls. One backend instance
 * assumed, like the rest of the pipeline — no cross-instance row claiming.
 */
@Component
@RequiredArgsConstructor
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;

    @Scheduled(fixedDelayString = "${app.outbox.poll-interval-ms:5000}")
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.findByStatusOrderByIdAsc(OutboxStatus.PENDING,
                Limit.of(properties.batchSize()));
        for (OutboxEvent event : batch) {
            send(event);
        }
    }

    static final String TRACE_HEADER = "traceId";

    /** One row at a time, synchronously: simpler than fanning out, and keeps a pack's events
     *  (same key, so same partition) in order even across a retry. */
    private void send(OutboxEvent event) {
        try {
            kafkaTemplate.send(toRecord(event)).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
            delete(event.getId());
            log.info("Published outbox event {} ({}) for key {}", event.getId(), event.getTopic(), event.getMessageKey());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordFailure(event.getId(), "Interrupted while sending");
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            recordFailure(event.getId(), describe(e));
        }
    }

    /** The traceId header (docs/study-packs-contract.md "Kafka") is the only thing that
     *  distinguishes this from the plain 3-arg send — carries the original request's correlation
     *  id (CorrelationIdFilter, via StudyPackService) across the async hop for log grepping. */
    private static ProducerRecord<String, String> toRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        if (event.getTraceId() != null) {
            record.headers().add(new RecordHeader(TRACE_HEADER, event.getTraceId().getBytes(StandardCharsets.UTF_8)));
        }
        return record;
    }

    @Transactional
    void delete(Long id) {
        outboxEventRepository.deleteById(id);
    }

    /** Re-reads the row (rather than reusing the caller's stale copy) since nothing else writes
     *  these rows, so this is only ever racing a previous attempt of the same poll — never a
     *  concurrent one, this being a single scheduled thread. */
    @Transactional
    void recordFailure(Long id, String message) {
        OutboxEvent event = outboxEventRepository.findById(id).orElse(null);
        if (event == null) {
            return;
        }
        int attempts = event.getAttempts() + 1;
        event.setAttempts(attempts);
        event.setLastError(truncate(message));
        event.setLastAttemptAt(LocalDateTime.now());
        if (attempts >= properties.maxAttempts()) {
            event.setStatus(OutboxStatus.FAILED);
            log.error("Giving up on outbox event {} ({}) for key {} after {} attempts: {}",
                    event.getId(), event.getTopic(), event.getMessageKey(), attempts, message);
        } else {
            log.warn("Outbox event {} ({}) send failed (attempt {}/{}): {}",
                    event.getId(), event.getTopic(), attempts, properties.maxAttempts(), message);
        }
    }

    /** The broker's own error, not the message payload (which may be large document-derived JSON). */
    private static String describe(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1000 ? s : s.substring(0, 1000);
    }
}
