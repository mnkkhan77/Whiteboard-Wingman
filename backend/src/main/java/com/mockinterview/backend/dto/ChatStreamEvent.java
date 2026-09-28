package com.mockinterview.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;

/**
 * The events of a pack chat answer stream (docs/study-packs-contract.md "SSE stream"), in order:
 * sources, delta*, then done or error. Transport-neutral: PackChatService produces these, and only
 * the controller turns them into SSE frames (event name + JSON data = the record itself).
 */
public sealed interface ChatStreamEvent {

    /** SSE event name. Not part of the JSON payload. */
    @JsonIgnore
    String eventName();

    record SourcesEvent(List<ChatSourceDto> sources) implements ChatStreamEvent {
        @Override
        public String eventName() {
            return "sources";
        }
    }

    record DeltaEvent(String text) implements ChatStreamEvent {
        @Override
        public String eventName() {
            return "delta";
        }
    }

    record DoneEvent(Long messageId, List<Integer> citedSources, ChatUsageDto usage, ChatQuotaDto quota)
            implements ChatStreamEvent {
        @Override
        public String eventName() {
            return "done";
        }
    }

    record ErrorEvent(String code, String message) implements ChatStreamEvent {

        public static final String LLM_RATE_LIMITED = "LLM_RATE_LIMITED";
        public static final String LLM_ERROR = "LLM_ERROR";

        @Override
        public String eventName() {
            return "error";
        }
    }
}
