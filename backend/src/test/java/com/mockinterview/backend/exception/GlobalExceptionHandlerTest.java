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
        RuntimeException sensitive = new RuntimeException("db connection string: jdbc:postgresql://internal-host/secret");

        ResponseEntity<java.util.Map<String, String>> response = handler.handleUnexpected(sensitive);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().get("message")).doesNotContain("jdbc:postgresql", "internal-host", "secret");
    }

    // MockMvc bypasses the servlet multipart size limit, so the 413 mapping for an upload over
    // spring.servlet.multipart.max-file-size is asserted on the handler directly.
    @Test
    void anUploadOverTheMultipartLimitMapsTo413FileTooLarge() {
        ResponseEntity<java.util.Map<String, String>> response =
                handler.handleMaxUploadSize(new org.springframework.web.multipart.MaxUploadSizeExceededException(209_715_200L));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("code", "FILE_TOO_LARGE").containsKey("message");
    }

    @Test
    void studyPackUploadRejectionsKeepTheirOwnStatusAndCode() {
        ResponseEntity<java.util.Map<String, String>> response = handler.handleStudyPackUpload(
                new StudyPackUploadException(HttpStatus.FORBIDDEN, StudyPackUploadException.PACK_LIMIT_REACHED, "limit"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).containsEntry("code", "PACK_LIMIT_REACHED").containsEntry("message", "limit");
    }
}
