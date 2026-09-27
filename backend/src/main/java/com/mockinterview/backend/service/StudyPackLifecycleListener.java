package com.mockinterview.backend.service;

import com.mockinterview.backend.kafka.DocumentEventPublisher;
import com.mockinterview.backend.kafka.DocumentUploadedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;

/**
 * Side effects of StudyPackService that must only happen once the DB transaction's outcome is
 * known. StudyPackService publishes these as Spring application events inside its transaction;
 * {@link TransactionalEventListener} defers them to the matching commit/rollback phase:
 *
 * - upload committed  -> publish wingman.document.uploaded. Publishing from inside the transaction
 *   would let the doc-processor (and then our own parsed consumer) see a packId whose row isn't
 *   committed yet — or never will be, if the commit then fails.
 * - upload rolled back -> delete the file already written to disk, so no orphan upload remains.
 * - delete committed  -> remove the pack's vectors and files. Done after commit so a failed delete
 *   never leaves a READY pack whose chunks are already gone.
 */
@Component
@RequiredArgsConstructor
public class StudyPackLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(StudyPackLifecycleListener.class);

    private final DocumentEventPublisher documentEventPublisher;
    private final StorageService storageService;
    private final VectorStore vectorStore;

    public record PackUploaded(DocumentUploadedEvent event) {
    }

    /** chunkCount may be null (not parsed yet): then no vectors can exist for it yet — and if
     *  embedding is running right now, PackEmbeddingService cleans up after itself on noticing
     *  the pack is gone. */
    public record PackDeleted(long packId, Integer chunkCount) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUploadCommitted(PackUploaded uploaded) {
        documentEventPublisher.publishUploaded(uploaded.event());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_ROLLBACK)
    public void onUploadRolledBack(PackUploaded uploaded) {
        storageService.deletePackDir(uploaded.event().packId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDeleteCommitted(PackDeleted deleted) {
        if (deleted.chunkCount() != null && deleted.chunkCount() > 0) {
            List<String> ids = PackEmbeddingService.vectorIds(deleted.packId(), deleted.chunkCount());
            try {
                vectorStore.delete(ids);
            } catch (RuntimeException e) {
                // The row is already gone, so there's nothing to retry against; orphaned vectors
                // are unreachable (pack retrieval always filters on a live packId), only wasted space.
                log.error("Failed to delete {} vectors for deleted pack {}", ids.size(), deleted.packId(), e);
            }
        }
        storageService.deletePackDir(deleted.packId());
    }
}
