package com.mockinterview.backend.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Every endpoint's error handling funnels through here rather than per-method try/catch — a
 * single boundary is easier to audit (e.g. for the "never leak the LLM key" claim in PLAN.md §8)
 * than the same logic scattered across every controller/service method. Handlers above are for
 * expected, named failure modes; handleUnexpected below is the catch-all so a genuine bug returns
 * a clean generic 500 instead of a stack trace, while the real detail still reaches the server log.
 *
 * Every body goes out with an explicit application/json content type ({@link #json}): the pack chat
 * endpoint is called with "Accept: text/event-stream", and without a preset type Spring's content
 * negotiation would find no JSON-capable match for that Accept and turn the refusal into a 406.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException e) {
        return json(HttpStatus.CONFLICT, Map.of("message", e.getMessage()));
    }

    // Distinct status + "code" (rather than folding into handleIllegalState) so the frontend can
    // reliably show a "sign up to continue" CTA instead of matching on message text.
    @ExceptionHandler(GuestAttemptLimitException.class)
    public ResponseEntity<Map<String, String>> handleGuestAttemptLimit(GuestAttemptLimitException e) {
        return json(HttpStatus.FORBIDDEN, Map.of(
                "message", e.getMessage(),
                "code", "GUEST_LIMIT_REACHED"
        ));
    }

    // Study Pack upload rejections — status and "code" come from the exception so each contract
    // code (docs/study-packs-contract.md) keeps its own HTTP status (403/413/400).
    @ExceptionHandler(StudyPackUploadException.class)
    public ResponseEntity<Map<String, String>> handleStudyPackUpload(StudyPackUploadException e) {
        return json(e.getStatus(), Map.of(
                "message", e.getMessage(),
                "code", e.getCode()
        ));
    }

    // Thrown by the multipart resolver before the controller runs, for a file over
    // spring.servlet.multipart.max-file-size (sized to the largest tier). Mapped to the same
    // FILE_TOO_LARGE code as the per-tier check in StudyPackService so the client sees one error.
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> handleMaxUploadSize(MaxUploadSizeExceededException e) {
        return json(HttpStatus.PAYLOAD_TOO_LARGE, Map.of(
                "message", "File is larger than the maximum upload size.",
                "code", StudyPackUploadException.FILE_TOO_LARGE
        ));
    }

    // Pack chat refusals before streaming (PACK_NOT_READY 409, CHAT_QUOTA_EXCEEDED 429,
    // CHAT_UNAVAILABLE 503) — same shape as the upload codes above.
    @ExceptionHandler(PackChatException.class)
    public ResponseEntity<Map<String, String>> handlePackChat(PackChatException e) {
        return json(e.getStatus(), Map.of(
                "message", e.getMessage(),
                "code", e.getCode()
        ));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException e) {
        return json(HttpStatus.NOT_FOUND, Map.of("message", e.getMessage()));
    }

    // Thrown by Spring AI for a 4xx from the provider — almost always a bad/rejected API key,
    // since PerRequestChatClientFactory always uses the caller's own key (PLAN.md §8).
    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, String>> handleNonTransientAi(NonTransientAiException e) {
        return json(HttpStatus.BAD_REQUEST, Map.of(
                "message", "The LLM provider rejected the request — check your API key and provider selection.",
                "providerDetail", e.getMessage()
        ));
    }

    // Thrown for a retryable provider-side failure (rate limit, timeout, 5xx).
    @ExceptionHandler(TransientAiException.class)
    public ResponseEntity<Map<String, String>> handleTransientAi(TransientAiException e) {
        return json(HttpStatus.BAD_GATEWAY, Map.of(
                "message", "The LLM provider is temporarily unavailable — please try again.",
                "providerDetail", e.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Validation failed");
        return json(HttpStatus.BAD_REQUEST, Map.of("message", message));
    }

    // Below: malformed-request cases Spring MVC would otherwise resolve to a 4xx automatically —
    // but once a catch-all Exception handler exists (below), it would shadow that default
    // resolution and turn these into a 500 instead. Named explicitly here so they keep behaving
    // like client errors, in the same {"message": ...} shape as everything else in this API.
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(MissingRequestHeaderException e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("message", "Malformed or missing request body"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, String>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("message", "Invalid value for parameter: " + e.getName()));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<Map<String, String>> handleMissingPart(MissingServletRequestPartException e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("message", "Missing multipart field: " + e.getRequestPartName()));
    }

    // Any other multipart parse failure (not multipart at all, truncated body...). Spring picks the
    // closest exception type, so MaxUploadSizeExceededException (a subclass) still maps to 413 above.
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, String>> handleMultipart(MultipartException e) {
        return json(HttpStatus.BAD_REQUEST, Map.of("message", "Malformed multipart request"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, String>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return json(HttpStatus.METHOD_NOT_ALLOWED, Map.of("message", e.getMessage()));
    }

    // Catch-all for anything not covered above — a genuine bug, not an expected failure mode.
    // Logged in full server-side; the client only ever sees a generic message, never the exception
    // detail or stack trace (which could otherwise leak internals through an API response).
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpected(Exception e) {
        log.error("Unhandled exception", e);
        return json(HttpStatus.INTERNAL_SERVER_ERROR, Map.of("message", "An unexpected error occurred. Please try again."));
    }

    private static ResponseEntity<Map<String, String>> json(HttpStatusCode status, Map<String, String> body) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
