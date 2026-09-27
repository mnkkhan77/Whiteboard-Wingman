package com.mockinterview.backend.kafka;

/**
 * wingman.document.parsed (doc-processor -> backend), docs/study-packs-contract.md. Claim-check:
 * the chunks themselves are in the file at chunksPath (relative to app.storage.root), not in the
 * message. occurredAt stays a String — it's informational only, and not parsing it means a
 * "+00:00" vs "Z" offset style from Python can never turn a valid event into a poison message.
 */
public record DocumentParsedEvent(
        String eventId,
        Long packId,
        Integer pageCount,
        String parser,
        Boolean ocrUsed,
        Integer chunkCount,
        String chunksPath,
        String occurredAt
) {
}
