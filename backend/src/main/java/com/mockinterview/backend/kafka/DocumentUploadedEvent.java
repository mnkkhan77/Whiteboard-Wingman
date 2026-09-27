package com.mockinterview.backend.kafka;

import com.mockinterview.backend.entity.Tier;

/**
 * wingman.document.uploaded (backend -> doc-processor), docs/study-packs-contract.md. The tier's
 * page/OCR limits travel with the event so the doc-processor never needs its own copy of
 * app.tiers.*. occurredAt is an ISO-8601 UTC string (e.g. 2026-09-27T10:15:30Z).
 */
public record DocumentUploadedEvent(
        String eventId,
        Long packId,
        Long ownerId,
        Tier tier,
        String fileName,
        String contentType,
        String storagePath,
        long sizeBytes,
        boolean ocrEnabled,
        int maxPages,
        String occurredAt
) {
}
