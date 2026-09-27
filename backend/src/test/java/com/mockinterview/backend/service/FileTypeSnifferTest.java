package com.mockinterview.backend.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Magic-byte checks that stop a renamed file from reaching the doc-processor as the wrong format. */
class FileTypeSnifferTest {

    static final byte[] PDF = "%PDF-1.7\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1);
    static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x06, 0x00};
    static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10};

    @Test
    void acceptsEachSupportedFormatWithItsOwnSignature() {
        assertThat(FileTypeSniffer.matches("pdf", PDF)).isTrue();
        assertThat(FileTypeSniffer.matches("docx", ZIP)).isTrue();
        assertThat(FileTypeSniffer.matches("pptx", ZIP)).isTrue();
        assertThat(FileTypeSniffer.matches("png", PNG)).isTrue();
        assertThat(FileTypeSniffer.matches("jpg", JPEG)).isTrue();
        assertThat(FileTypeSniffer.matches("jpeg", JPEG)).isTrue();
    }

    @Test
    void extensionMatchingIsCaseInsensitive() {
        assertThat(FileTypeSniffer.matches("PDF", PDF)).isTrue();
    }

    @Test
    void rejectsARenamedFile() {
        assertThat(FileTypeSniffer.matches("pdf", PNG)).isFalse();
        assertThat(FileTypeSniffer.matches("pdf", "MZ\u0090\u0000 not a pdf".getBytes(StandardCharsets.ISO_8859_1))).isFalse();
        assertThat(FileTypeSniffer.matches("docx", PDF)).isFalse();
        assertThat(FileTypeSniffer.matches("png", JPEG)).isFalse();
        assertThat(FileTypeSniffer.matches("jpg", PNG)).isFalse();
    }

    @Test
    void allowsJunkBeforeThePdfHeaderWithinTheFirstKilobyte() {
        byte[] data = new byte[600];
        System.arraycopy(PDF, 0, data, 500, 5);
        assertThat(FileTypeSniffer.matches("pdf", data)).isTrue();
    }

    @Test
    void rejectsTruncatedHeadersAndUnknownExtensions() {
        assertThat(FileTypeSniffer.matches("png", new byte[]{(byte) 0x89, 0x50})).isFalse();
        assertThat(FileTypeSniffer.matches("pdf", new byte[0])).isFalse();
        assertThat(FileTypeSniffer.matches("exe", PDF)).isFalse();
        assertThat(FileTypeSniffer.matches(null, PDF)).isFalse();
    }

    @Test
    void mapsExtensionsToCanonicalContentTypes() {
        assertThat(FileTypeSniffer.contentTypeFor("pdf")).isEqualTo("application/pdf");
        assertThat(FileTypeSniffer.contentTypeFor("JPG")).isEqualTo("image/jpeg");
        assertThat(FileTypeSniffer.contentTypeFor("docx"))
                .isEqualTo("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
    }
}
