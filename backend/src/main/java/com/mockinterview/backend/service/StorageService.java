package com.mockinterview.backend.service;

import com.mockinterview.backend.config.StorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local-disk storage shared with the doc-processor (docs/study-packs-contract.md "Shared
 * storage"). Every path crossing a service boundary is relative to app.storage.root with forward
 * slashes, so the backend (host path) and doc-processor (container mount) agree on it. Relative
 * paths also arrive in Kafka events from another process, so {@link #resolve} refuses anything
 * that escapes the root rather than trusting the sender.
 */
@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final Pattern SAFE_EXTENSION = Pattern.compile("[a-z0-9]{1,10}");

    private final Path root;

    public StorageService(StorageProperties properties) {
        this.root = Paths.get(properties.root()).toAbsolutePath().normalize();
    }

    /** Root-relative path of a pack's uploaded source file: packs/{packId}/source.{ext}. The one
     *  place that layout is spelled out — the DB row and the uploaded event both carry it. */
    public static String packSourcePath(long packId, String extension) {
        return packPrefix(packId) + "/source." + extension;
    }

    /** Writes an upload to {@link #packSourcePath} and returns that relative path. */
    public String writePackSource(long packId, String extension, InputStream content) {
        if (extension == null || !SAFE_EXTENSION.matcher(extension).matches()) {
            throw new IllegalArgumentException("Invalid file extension");
        }
        String relative = packSourcePath(packId, extension);
        Path target = resolve(relative);
        try {
            Files.createDirectories(target.getParent());
            // Write to a temp name and move into place so the doc-processor can never observe
            // (or a crash leave behind) a half-written source file under the expected name.
            Path tmp = Files.createTempFile(target.getParent(), "upload-", ".part");
            try {
                Files.copy(content, tmp, StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store upload for pack " + packId, e);
        }
        return relative;
    }

    /** Opens a stored file (e.g. the doc-processor's chunks.json) by its root-relative path. */
    public InputStream open(String relativePath) throws IOException {
        return Files.newInputStream(resolve(relativePath));
    }

    /** True when relativePath resolves inside packs/{packId}/ — the only place a pack's own
     *  events may point, so one pack's event can't make the backend read another pack's files. */
    public boolean isWithinPack(long packId, String relativePath) {
        try {
            return resolve(relativePath).startsWith(resolve(packPrefix(packId)));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Deletes packs/{packId}/ recursively (source, chunks.json, anything else the doc-processor
     *  left there). Missing directory is not an error — deletion must be idempotent. */
    public void deletePackDir(long packId) {
        Path dir = resolve(packPrefix(packId));
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not fully delete storage for pack {}: {}", packId, e.getMessage());
        }
    }

    /**
     * Resolves a root-relative path, rejecting absolute paths, drive letters and any ".."
     * sequence that normalizes to somewhere outside the root.
     */
    Path resolve(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Empty storage path");
        }
        String normalizedSeparators = relativePath.replace('\\', '/');
        if (normalizedSeparators.startsWith("/") || normalizedSeparators.contains(":")
                || normalizedSeparators.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Storage path must be relative: " + relativePath);
        }
        Path resolved = root.resolve(normalizedSeparators).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IllegalArgumentException("Storage path escapes the storage root: " + relativePath);
        }
        return resolved;
    }

    private static String packPrefix(long packId) {
        return "packs/" + packId;
    }
}
