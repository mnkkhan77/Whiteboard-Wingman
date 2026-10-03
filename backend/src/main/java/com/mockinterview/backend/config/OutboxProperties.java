package com.mockinterview.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * app.outbox.* — the transactional outbox poller (OutboxPublisher). pollIntervalMs is also read
 * directly as a property placeholder on the @Scheduled method (property binding to a Duration
 * field doesn't help there — the annotation needs a plain millisecond literal), so the two must
 * stay the same key.
 *
 * @param pollIntervalMs how often to look for pending rows
 * @param batchSize      pending rows claimed per poll, oldest first
 * @param sendTimeout    how long to wait for the broker to ack one send before counting it failed
 * @param maxAttempts    attempts before a row is given up on (marked FAILED, left for an operator)
 */
@ConfigurationProperties(prefix = "app.outbox")
public record OutboxProperties(
        Integer pollIntervalMs,
        Integer batchSize,
        Duration sendTimeout,
        Integer maxAttempts
) {
    public OutboxProperties {
        pollIntervalMs = pollIntervalMs == null ? 5000 : pollIntervalMs;
        batchSize = batchSize == null ? 50 : batchSize;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(10) : sendTimeout;
        maxAttempts = maxAttempts == null ? 10 : maxAttempts;
    }
}
