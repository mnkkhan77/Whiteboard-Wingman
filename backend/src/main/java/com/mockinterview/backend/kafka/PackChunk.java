package com.mockinterview.backend.kafka;

/** One element of the doc-processor's chunks.json array (docs/study-packs-contract.md). page,
 *  pageEnd and section are null for formats without pages/headings. */
public record PackChunk(
        Integer index,
        String text,
        Integer page,
        Integer pageEnd,
        String section,
        String elementType
) {
}
