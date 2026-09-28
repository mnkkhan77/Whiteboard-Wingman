package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.QuizStatus;
import com.mockinterview.backend.entity.StudyPack;
import com.mockinterview.backend.entity.StudyPackStatus;
import com.mockinterview.backend.entity.User;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The status-transition methods below are conditional bulk updates, each returning the number of
 * rows changed. The Kafka consumers only ever move a pack forward through them, so "0 rows" is the
 * single, race-free signal that the pack was deleted meanwhile or already reached a terminal
 * state (a redelivered event) — no read-then-save window where a concurrent DELETE or a second
 * delivery could slip in, and no risk of save() re-inserting a row the user just deleted.
 */
public interface StudyPackRepository extends JpaRepository<StudyPack, Long> {

    List<StudyPack> findByOwnerOrderByCreatedAtDescIdDesc(User owner);

    Optional<StudyPack> findByIdAndOwner(Long id, User owner);

    boolean existsByIdAndOwner(Long id, User owner);

    long countByOwner(User owner);

    /** The embedding step needs the owner's current tier (chunk cap) outside any transaction. */
    @EntityGraph(attributePaths = "owner")
    Optional<StudyPack> findWithOwnerById(Long id);

    @Transactional
    @Modifying
    @Query("update StudyPack p set p.status = :to, p.updatedAt = :now where p.id = :id and p.status in :from")
    int transition(@Param("id") Long id,
                   @Param("from") Collection<StudyPackStatus> from,
                   @Param("to") StudyPackStatus to,
                   @Param("now") LocalDateTime now);

    /** Records what the doc-processor reported, while the pack is still EMBEDDING — chunkCount is
     *  stored before any vector is written so a DELETE mid-embedding can still rebuild every
     *  chunk's vector id. */
    @Transactional
    @Modifying
    @Query("""
            update StudyPack p set p.pageCount = :pageCount, p.parser = :parser, p.ocrUsed = :ocrUsed,
                   p.chunkCount = :chunkCount, p.updatedAt = :now
            where p.id = :id and p.status = com.mockinterview.backend.entity.StudyPackStatus.EMBEDDING
            """)
    int recordParseResult(@Param("id") Long id,
                          @Param("pageCount") Integer pageCount,
                          @Param("parser") String parser,
                          @Param("ocrUsed") Boolean ocrUsed,
                          @Param("chunkCount") Integer chunkCount,
                          @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("""
            update StudyPack p set p.status = com.mockinterview.backend.entity.StudyPackStatus.FAILED,
                   p.errorCode = :errorCode, p.errorMessage = :errorMessage, p.updatedAt = :now
            where p.id = :id and p.status in (com.mockinterview.backend.entity.StudyPackStatus.QUEUED,
                                              com.mockinterview.backend.entity.StudyPackStatus.EMBEDDING)
            """)
    int markFailed(@Param("id") Long id,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("now") LocalDateTime now);

    /** Id + title only — all a session list, report or progress chart shows of a pack. */
    record PackTitle(Long id, String title) {
    }

    @Query("select new com.mockinterview.backend.repository.StudyPackRepository$PackTitle(p.id, p.title) "
            + "from StudyPack p where p.id in :ids")
    List<PackTitle> findTitlesByIdIn(@Param("ids") Collection<Long> ids);

    // ---- Question bank (docs/study-packs-contract.md "Quiz from a pack"). Same conditional-update
    // style as the pipeline transitions above: "0 rows" is the race-free "not allowed / someone
    // else got there first / pack deleted" signal.

    /** Enters GENERATING only from a READY pack whose bank isn't already generating — the one
     *  gate that makes two simultaneous generate requests start exactly one job. */
    @Transactional
    @Modifying
    @Query("""
            update StudyPack p set p.quizStatus = com.mockinterview.backend.entity.QuizStatus.GENERATING,
                   p.quizErrorMessage = null, p.updatedAt = :now
            where p.id = :id and p.status = com.mockinterview.backend.entity.StudyPackStatus.READY
              and p.quizStatus <> com.mockinterview.backend.entity.QuizStatus.GENERATING
            """)
    int startQuizGeneration(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** Joins PackQuizBankWriter's transaction, where it also row-locks the pack before the bank
     *  rows are swapped, so a concurrent pack DELETE waits instead of racing the inserts. */
    @Transactional
    @Modifying
    @Query("""
            update StudyPack p set p.quizStatus = com.mockinterview.backend.entity.QuizStatus.READY,
                   p.quizQuestionCount = :count, p.quizErrorMessage = null, p.updatedAt = :now
            where p.id = :id and p.quizStatus = com.mockinterview.backend.entity.QuizStatus.GENERATING
            """)
    int finishQuizGeneration(@Param("id") Long id, @Param("count") int count, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("""
            update StudyPack p set p.quizStatus = com.mockinterview.backend.entity.QuizStatus.FAILED,
                   p.quizErrorMessage = :message, p.updatedAt = :now
            where p.id = :id and p.quizStatus = com.mockinterview.backend.entity.QuizStatus.GENERATING
            """)
    int failQuizGeneration(@Param("id") Long id, @Param("message") String message, @Param("now") LocalDateTime now);

    /** Startup only: a job can't survive a restart (it lives on an in-memory executor). */
    @Transactional
    @Modifying
    @Query("""
            update StudyPack p set p.quizStatus = com.mockinterview.backend.entity.QuizStatus.FAILED,
                   p.quizErrorMessage = :message, p.updatedAt = :now
            where p.quizStatus = :generating
            """)
    int failAllQuizGenerations(@Param("generating") QuizStatus generating, @Param("message") String message,
                               @Param("now") LocalDateTime now);
}
