package com.mockinterview.backend.service;

import com.mockinterview.backend.config.StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Local storage layout (packs/{id}/source.{ext}) and path-traversal refusal — relative paths also
 *  arrive from the doc-processor over Kafka, so they're never trusted. */
class StorageServiceTest {

    @TempDir Path root;
    private StorageService storage;

    @BeforeEach
    void setUp() {
        storage = new StorageService(new StorageProperties(root.toString()));
    }

    @Test
    void writesTheSourceUnderThePackDirAndReturnsTheRelativePath() throws Exception {
        String path = storage.writePackSource(42, "pdf", new ByteArrayInputStream("%PDF-x".getBytes(StandardCharsets.US_ASCII)));

        assertThat(path).isEqualTo("packs/42/source.pdf");
        assertThat(Files.readString(root.resolve("packs/42/source.pdf"))).isEqualTo("%PDF-x");
        try (InputStream in = storage.open(path)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("%PDF-x");
        }
        try (var files = Files.list(root.resolve("packs/42"))) {
            assertThat(files).hasSize(1); // no temp ".part" file left behind
        }
    }

    @Test
    void rejectsPathsThatEscapeTheRoot() {
        for (String bad : new String[]{"../secret", "packs/../../secret", "/etc/passwd", "C:/Windows/win.ini",
                "\\\\server\\share", "", "packs/.."}) {
            assertThatThrownBy(() -> storage.resolve(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> storage.writePackSource(1, "../x", InputStream.nullInputStream()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void isWithinPackOnlyAcceptsPathsInsideThatPacksDirectory() {
        assertThat(storage.isWithinPack(42, "packs/42/chunks.json")).isTrue();
        assertThat(storage.isWithinPack(42, "packs/43/chunks.json")).isFalse();
        assertThat(storage.isWithinPack(42, "packs/42/../43/chunks.json")).isFalse();
        assertThat(storage.isWithinPack(42, "packs/420/chunks.json")).isFalse();
        assertThat(storage.isWithinPack(42, "../../etc/passwd")).isFalse();
    }

    @Test
    void deletePackDirRemovesEverythingAndIsIdempotent() throws Exception {
        storage.writePackSource(7, "pdf", new ByteArrayInputStream(new byte[]{1}));
        Files.writeString(root.resolve("packs/7/chunks.json"), "[]");

        storage.deletePackDir(7);
        storage.deletePackDir(7);

        assertThat(root.resolve("packs/7")).doesNotExist();
    }
}
