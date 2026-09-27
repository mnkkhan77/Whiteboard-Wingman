package com.mockinterview.backend.entity;

/** QUEUED -> EMBEDDING -> READY, or FAILED from any non-terminal step (docs/study-packs-contract.md).
 *  READY and FAILED are terminal: Kafka redeliveries for a pack in either state are ignored. */
public enum StudyPackStatus {
    QUEUED, EMBEDDING, READY, FAILED;

    public boolean isTerminal() {
        return this == READY || this == FAILED;
    }
}
