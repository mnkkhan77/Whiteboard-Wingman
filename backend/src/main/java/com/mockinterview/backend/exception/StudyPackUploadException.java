package com.mockinterview.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A rejected Study Pack upload, carrying the contract's machine-readable code
 * (docs/study-packs-contract.md "Upload error codes") so the frontend can branch on it — e.g. an
 * "upgrade your tier" CTA for PACK_LIMIT_REACHED — instead of matching on message text.
 */
@Getter
public class StudyPackUploadException extends RuntimeException {

    public static final String GUEST_UPLOAD_NOT_ALLOWED = "GUEST_UPLOAD_NOT_ALLOWED";
    public static final String PACK_LIMIT_REACHED = "PACK_LIMIT_REACHED";
    public static final String FILE_TOO_LARGE = "FILE_TOO_LARGE";
    public static final String UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT";
    public static final String EMPTY_FILE = "EMPTY_FILE";

    private final HttpStatus status;
    private final String code;

    public StudyPackUploadException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
