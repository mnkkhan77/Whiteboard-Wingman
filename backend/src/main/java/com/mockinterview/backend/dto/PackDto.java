package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;

import java.time.LocalDateTime;

/** Study Pack as returned by /api/packs (docs/study-packs-contract.md "PackDto"). Storage paths and
 *  owner ids stay server-side. */
public record PackDto(
        Long id,
        String title,
        String fileName,
        StudyPackStatus status,
        long sizeBytes,
        Integer pageCount,
        Integer chunkCount,
        String parser,
        Boolean ocrUsed,
        String errorCode,
        String errorMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static PackDto from(StudyPack pack) {
        return new PackDto(
                pack.getId(), pack.getTitle(), pack.getFileName(), pack.getStatus(), pack.getSizeBytes(),
                pack.getPageCount(), pack.getChunkCount(), pack.getParser(), pack.getOcrUsed(),
                pack.getErrorCode(), pack.getErrorMessage(), pack.getCreatedAt(), pack.getUpdatedAt());
    }
}
