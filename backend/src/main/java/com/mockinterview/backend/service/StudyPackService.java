package com.mockinterview.backend.service;

import com.mockinterview.backend.config.TierProperties;
import com.mockinterview.backend.config.TierProperties.TierLimits;
import com.mockinterview.backend.dto.PackDto;
import com.mockinterview.backend.dto.PackLimitsDto;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.exception.StudyPackUploadException;
import com.mockinterview.backend.kafka.DocumentUploadedEvent;
import com.mockinterview.backend.repository.StudyPackRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.UUID;

import static com.mockinterview.backend.exception.StudyPackUploadException.*;

/**
 * Study Pack CRUD plus the upload-time tier checks (docs/study-packs-contract.md). Everything
 * after upload — parsing, embedding, status changes — is asynchronous, driven by Kafka events
 * (DocumentEventsListener / PackEmbeddingService); this class only ever creates packs as QUEUED.
 */
@Service
@RequiredArgsConstructor
public class StudyPackService {

    private static final int MAX_NAME_LENGTH = 255;

    private final StudyPackRepository studyPackRepository;
    private final StorageService storageService;
    private final TierProperties tierProperties;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public PackLimitsDto limits(User user) {
        TierLimits limits = tierProperties.forTier(user.getTier());
        return new PackLimitsDto(user.getTier(), limits.maxFileBytes(), limits.maxPages(), limits.maxPacks(),
                limits.maxChunksPerPack(), limits.ocrEnabled(), limits.allowedExtensions(),
                studyPackRepository.countByOwner(user));
    }

    @Transactional(readOnly = true)
    public List<PackDto> list(User user) {
        return studyPackRepository.findByOwnerOrderByCreatedAtDescIdDesc(user).stream().map(PackDto::from).toList();
    }

    @Transactional(readOnly = true)
    public PackDto get(User user, Long packId) {
        return PackDto.from(findOwned(user, packId));
    }

    /**
     * Validates, stores and enqueues an upload. The file is written inside the transaction (the
     * path needs the generated id); if the transaction then rolls back, StudyPackLifecycleListener
     * deletes the file again, and the Kafka event is only published once the row has committed.
     */
    @Transactional
    public PackDto upload(User user, MultipartFile file, String title) {
        TierLimits limits = tierProperties.forTier(user.getTier());
        String fileName = sanitizeFileName(file.getOriginalFilename());
        String extension = extensionOf(fileName);
        checkUpload(user, limits, studyPackRepository.countByOwner(user), file.getSize(), extension, readHeader(file));

        StudyPack pack = new StudyPack();
        pack.setOwner(user);
        pack.setFileName(fileName);
        pack.setTitle(resolveTitle(title, fileName));
        pack.setExtension(extension);
        pack.setContentType(FileTypeSniffer.contentTypeFor(extension));
        pack.setSizeBytes(file.getSize());
        pack.setStatus(StudyPackStatus.QUEUED);
        pack = studyPackRepository.save(pack); // IDENTITY: inserts now, so the id is known

        // Registered before the write so a failure part-way through the write still cleans up.
        eventPublisher.publishEvent(new StudyPackLifecycleListener.PackUploaded(uploadedEvent(user, limits, pack)));
        try (InputStream in = file.getInputStream()) {
            pack.setStoragePath(storageService.writePackSource(pack.getId(), extension, in));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
        return PackDto.from(pack);
    }

    @Transactional
    public void delete(User user, Long packId) {
        StudyPack pack = findOwned(user, packId);
        studyPackRepository.delete(pack);
        eventPublisher.publishEvent(new StudyPackLifecycleListener.PackDeleted(pack.getId(), pack.getChunkCount()));
    }

    /**
     * Tier gate, in the order the contract lists the codes: who (guest), how many (pack count),
     * then the file itself (empty, size, format, magic bytes). The pack-count check is a plain
     * count, so two simultaneous uploads could both pass at maxPacks-1 — an accepted one-pack
     * overshoot rather than serializing uploads per user with a row lock.
     */
    static void checkUpload(User user, TierLimits limits, long packsUsed, long sizeBytes,
                            String extension, byte[] header) {
        if (user.isGuest()) {
            throw new StudyPackUploadException(HttpStatus.FORBIDDEN, GUEST_UPLOAD_NOT_ALLOWED,
                    "Guest accounts can't upload study packs — sign up for a free account to upload.");
        }
        if (!limits.unlimitedPacks() && packsUsed >= limits.maxPacks()) {
            throw new StudyPackUploadException(HttpStatus.FORBIDDEN, PACK_LIMIT_REACHED,
                    "Your " + user.getTier() + " tier allows " + limits.maxPacks()
                            + " study packs. Delete one or upgrade to upload more.");
        }
        if (sizeBytes <= 0) {
            throw new StudyPackUploadException(HttpStatus.BAD_REQUEST, EMPTY_FILE, "The uploaded file is empty.");
        }
        if (sizeBytes > limits.maxFileBytes()) {
            throw new StudyPackUploadException(HttpStatus.PAYLOAD_TOO_LARGE, FILE_TOO_LARGE,
                    "File is " + sizeBytes + " bytes; your " + user.getTier() + " tier allows "
                            + limits.maxFileBytes() + ".");
        }
        if (!limits.allowsExtension(extension)) {
            throw new StudyPackUploadException(HttpStatus.BAD_REQUEST, UNSUPPORTED_FORMAT,
                    "Unsupported file type" + (extension.isEmpty() ? "" : " ." + extension)
                            + " for your " + user.getTier() + " tier. Allowed: "
                            + String.join(", ", limits.allowedExtensions()) + ".");
        }
        if (!FileTypeSniffer.matches(extension, header)) {
            throw new StudyPackUploadException(HttpStatus.BAD_REQUEST, UNSUPPORTED_FORMAT,
                    "The file's contents don't match its ." + extension + " extension.");
        }
    }

    private StudyPack findOwned(User user, Long packId) {
        // Same 404 for "doesn't exist" and "not yours", so pack ids can't be probed.
        return studyPackRepository.findByIdAndOwner(packId, user)
                .orElseThrow(() -> new NoSuchElementException("Study pack not found"));
    }

    private static DocumentUploadedEvent uploadedEvent(User user, TierLimits limits, StudyPack pack) {
        return new DocumentUploadedEvent(
                UUID.randomUUID().toString(), pack.getId(), user.getId(), user.getTier(), pack.getFileName(),
                pack.getContentType(), StorageService.packSourcePath(pack.getId(), pack.getExtension()),
                pack.getSizeBytes(), limits.ocrEnabled(), limits.maxPages(),
                Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
    }

    private static byte[] readHeader(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            return in.readNBytes(FileTypeSniffer.HEADER_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
    }

    /** Keeps only the last path segment (some browsers send a full client path) and drops control
     *  characters; the name is display-only — the stored file is always source.{ext}. */
    static String sanitizeFileName(String original) {
        String name = original == null ? "" : original;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = name.replaceAll("\\p{Cntrl}", "").trim();
        if (name.isEmpty()) {
            name = "upload";
        }
        return truncate(name, MAX_NAME_LENGTH);
    }

    static String extensionOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 || dot == fileName.length() - 1 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String resolveTitle(String title, String fileName) {
        if (title != null && !title.isBlank()) {
            return truncate(title.trim(), MAX_NAME_LENGTH);
        }
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        return truncate(base, MAX_NAME_LENGTH);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
