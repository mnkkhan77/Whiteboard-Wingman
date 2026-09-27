package com.mockinterview.backend.kafka;

/** wingman.document.failed (doc-processor -> backend), docs/study-packs-contract.md. errorCode is
 *  one of the doc-processor codes there (PAGE_LIMIT_EXCEEDED, OCR_REQUIRED, ...) and is stored on
 *  the pack verbatim. */
public record DocumentFailedEvent(
        String eventId,
        Long packId,
        String errorCode,
        String message,
        String occurredAt
) {
}
