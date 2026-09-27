package com.mockinterview.backend.service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/**
 * Checks a file's leading "magic bytes" against the format its extension claims, so a renamed
 * file (e.g. an .exe or .zip saved as notes.pdf) is rejected at upload rather than handed to the
 * doc-processor's parsers. Deliberately shallow — it proves the container format, not that the
 * document is well-formed (that's the doc-processor's PARSE_ERROR).
 */
public final class FileTypeSniffer {

    /** Bytes callers should read from the start of the file before calling {@link #matches}. */
    public static final int HEADER_BYTES = 1024;

    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    // DOCX/PPTX are OOXML, i.e. ZIP archives: local file header "PK\3\4".
    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg"
    );

    private FileTypeSniffer() {
    }

    /** True when {@code header} (the first bytes of the file) is consistent with {@code extension}.
     *  Unknown extensions never match, so adding a tier format also requires teaching this class. */
    public static boolean matches(String extension, byte[] header) {
        if (extension == null || header == null) {
            return false;
        }
        return switch (extension.toLowerCase(Locale.ROOT)) {
            // The PDF spec lets "%PDF-" appear anywhere in the first 1024 bytes (some generators
            // prepend junk), so search the header rather than requiring offset 0.
            case "pdf" -> indexOf(header, PDF) >= 0;
            case "docx", "pptx" -> startsWith(header, ZIP);
            case "png" -> startsWith(header, PNG);
            case "jpg", "jpeg" -> startsWith(header, JPEG);
            default -> false;
        };
    }

    /** Canonical MIME type for a supported extension — stored and sent downstream instead of the
     *  client's Content-Type header, which browsers often leave as application/octet-stream. */
    public static String contentTypeFor(String extension) {
        return CONTENT_TYPES.getOrDefault(extension.toLowerCase(Locale.ROOT), "application/octet-stream");
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int indexOf(byte[] data, byte[] needle) {
        int limit = Math.min(data.length, HEADER_BYTES) - needle.length;
        outer:
        for (int i = 0; i <= limit; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
