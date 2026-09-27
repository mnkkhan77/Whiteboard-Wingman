package com.mockinterview.backend.kafka;

/** A structurally invalid Kafka event (e.g. no packId). Registered as non-retryable in
 *  KafkaConfig, so it goes straight to the dead-letter topic. */
public class InvalidDocumentEventException extends RuntimeException {
    public InvalidDocumentEventException(String message) {
        super(message);
    }
}
