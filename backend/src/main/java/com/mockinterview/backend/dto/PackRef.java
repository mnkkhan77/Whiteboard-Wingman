package com.mockinterview.backend.dto;

/**
 * The pack a session quizzes on, as shown next to it (docs/study-packs-contract.md "DTO
 * additions"): both null for handbook-topic sessions. packTitle alone can be null too, once the
 * pack has been deleted (the session keeps topic STUDY_PACK).
 */
public record PackRef(Long packId, String packTitle) {

    public static final PackRef NONE = new PackRef(null, null);

    /** For the unauthenticated shared report: the title is shown, the id is not handed out. */
    public PackRef titleOnly() {
        return new PackRef(null, packTitle);
    }
}
