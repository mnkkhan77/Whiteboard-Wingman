package com.mockinterview.backend.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The catch-all handler is the safety net for any bug not covered by a named handler above it —
 * it must never leak the raw exception message (which could contain internal details) to the
 * client, only a generic message. The real detail still goes to the server log (verified in the
 * production code path, not here — logging output isn't worth asserting on).
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void unexpectedExceptionsReturnAGenericMessageNotTheRawExceptionDetail() {
        RuntimeException sensitive = new RuntimeException("db connection string: jdbc:mysql://internal-host/secret");

        ResponseEntity<java.util.Map<String, String>> response = handler.handleUnexpected(sensitive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).doesNotContain("jdbc:mysql", "internal-host", "secret");
    }
}
