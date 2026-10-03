package com.mockinterview.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A study-pack LLM request refused up front — a pack chat message before any answer is streamed,
 * or a quiz bank generation / quiz start — carrying the contract's machine-readable code
 * (docs/study-packs-contract.md "Chat with a pack" and "Quiz from a pack" REST) so the frontend can
 * branch on it — e.g. an upgrade CTA for CHAT_QUOTA_EXCEEDED. Both features share the server key
 * and the monthly token budget, hence one exception type for both.
 */
@Getter
public class PackChatException extends RuntimeException {

    public static final String PACK_NOT_READY = "PACK_NOT_READY";
    public static final String CHAT_QUOTA_EXCEEDED = "CHAT_QUOTA_EXCEEDED";
    public static final String CHAT_UNAVAILABLE = "CHAT_UNAVAILABLE";
    public static final String QUIZ_NOT_READY = "QUIZ_NOT_READY";
    public static final String QUIZ_ALREADY_GENERATING = "QUIZ_ALREADY_GENERATING";
    public static final String FLASHCARDS_NOT_READY = "FLASHCARDS_NOT_READY";
    public static final String FLASHCARDS_ALREADY_GENERATING = "FLASHCARDS_ALREADY_GENERATING";
    /** Server-key provider failures while grading / reporting a pack quiz (same codes as the chat
     *  stream's error event). Never carries the provider's own error body: that is about the
     *  server's account, not the user's. */
    public static final String LLM_RATE_LIMITED = "LLM_RATE_LIMITED";
    public static final String LLM_ERROR = "LLM_ERROR";

    private final HttpStatus status;
    private final String code;

    public PackChatException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
