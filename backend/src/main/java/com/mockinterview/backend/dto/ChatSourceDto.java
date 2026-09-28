package com.mockinterview.backend.dto;

/**
 * One numbered source of a pack chat answer (docs/study-packs-contract.md "ChatSourceDto"): the
 * answer's [n] markers refer to sources[n-1]. Also the JSONB shape stored in
 * pack_chat_messages.sources, so history shows exactly what the answer saw.
 */
public record ChatSourceDto(int n, Integer page, Integer pageEnd, String section, String snippet) {
}
