package com.mockinterview.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A pack chat request refused before any answer is streamed, carrying the contract's
 * machine-readable code (docs/study-packs-contract.md "Chat with a pack" REST) so the frontend can
 * branch on it — e.g. an upgrade CTA for CHAT_QUOTA_EXCEEDED.
 */
@Getter
public class PackChatException extends RuntimeException {

    public static final String PACK_NOT_READY = "PACK_NOT_READY";
    public static final String CHAT_QUOTA_EXCEEDED = "CHAT_QUOTA_EXCEEDED";
    public static final String CHAT_UNAVAILABLE = "CHAT_UNAVAILABLE";

    private final HttpStatus status;
    private final String code;

    public PackChatException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
