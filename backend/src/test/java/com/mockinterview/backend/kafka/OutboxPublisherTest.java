package com.mockinterview.backend.kafka;

import com.mockinterview.backend.config.OutboxProperties;
import com.mockinterview.backend.entity.OutboxEvent;
import com.mockinterview.backend.entity.OutboxStatus;
import com.mockinterview.backend.repository.OutboxEventRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Limit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** OutboxPublisher's send/retry/give-up logic, with the repository and broker mocked — no DB or
 *  Kafka needed (StudyPackKafkaIntegrationTest covers the real end-to-end wire). */
class OutboxPublisherTest {

    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);
    private final OutboxProperties properties = new OutboxProperties(5000, 50, Duration.ofSeconds(1), 3);
    private final OutboxPublisher publisher = new OutboxPublisher(repository, kafkaTemplate, properties);

    private static OutboxEvent pending(long id, int attempts) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setTopic("wingman.document.uploaded");
        event.setMessageKey(String.valueOf(id));
        event.setPayload("{}");
        event.setStatus(OutboxStatus.PENDING);
        event.setAttempts(attempts);
        return event;
    }

    @SuppressWarnings("unchecked")
    private static SendResult<String, String> sendResult() {
        return mock(SendResult.class);
    }

    private void stubBatch(OutboxEvent event) {
        when(repository.findByStatusOrderByIdAsc(eq(OutboxStatus.PENDING), any(Limit.class))).thenReturn(List.of(event));
    }

    @SuppressWarnings("unchecked")
    private void stubSend(CompletableFuture<SendResult<String, String>> result) {
        when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(result);
    }

    @Test
    void aSuccessfulSendDeletesTheRowAndRecordsNoFailure() {
        OutboxEvent event = pending(1, 0);
        stubBatch(event);
        stubSend(CompletableFuture.completedFuture(sendResult()));

        publisher.publishPending();

        verify(repository).deleteById(1L);
        verify(repository, never()).findById(any());
    }

    @Test
    void aFailedSendIncrementsAttemptsRecordsTheErrorAndStaysPending() {
        OutboxEvent event = pending(2, 0);
        stubBatch(event);
        stubSend(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(repository.findById(2L)).thenReturn(Optional.of(event));

        publisher.publishPending();

        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(event.getLastError()).contains("broker down");
        assertThat(event.getLastAttemptAt()).isNotNull();
        verify(repository, never()).deleteById(any());
    }

    @Test
    void givesUpAndMarksFailedOnceMaxAttemptsIsReached() {
        OutboxEvent event = pending(3, 2); // one more failure reaches maxAttempts=3
        stubBatch(event);
        stubSend(CompletableFuture.failedFuture(new RuntimeException("broker down")));
        when(repository.findById(3L)).thenReturn(Optional.of(event));

        publisher.publishPending();

        assertThat(event.getAttempts()).isEqualTo(3);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void theTraceIdIsSentAsAKafkaHeaderWhenPresentButOmittedWhenAbsent() {
        OutboxEvent withTrace = pending(4, 0);
        withTrace.setTraceId("abc-123");
        stubBatch(withTrace);
        stubSend(CompletableFuture.completedFuture(sendResult()));

        publisher.publishPending();

        ArgumentCaptor<ProducerRecord<String, String>> captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafkaTemplate).send(captor.capture());
        assertThat(captor.getValue().headers().lastHeader(OutboxPublisher.TRACE_HEADER).value())
                .isEqualTo("abc-123".getBytes(StandardCharsets.UTF_8));

        OutboxEvent withoutTrace = pending(5, 0);
        stubBatch(withoutTrace);

        publisher.publishPending();

        verify(kafkaTemplate, times(2)).send(captor.capture());
        assertThat(captor.getValue().headers().lastHeader(OutboxPublisher.TRACE_HEADER)).isNull();
    }
}
