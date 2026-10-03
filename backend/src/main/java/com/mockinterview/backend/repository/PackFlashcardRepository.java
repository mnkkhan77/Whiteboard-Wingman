package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.PackFlashcard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PackFlashcardRepository extends JpaRepository<PackFlashcard, Long> {

    List<PackFlashcard> findByPackIdOrderByIdAsc(Long packId);

    Optional<PackFlashcard> findByIdAndPackId(Long id, Long packId);

    long countByPackIdAndDueAtLessThanEqual(Long packId, LocalDateTime now);

    /** Bulk delete for replace-on-regenerate — a derived deleteBy would load every row first.
     *  Only called inside PackFlashcardBankWriter's transaction. */
    @Modifying
    @Query("delete from PackFlashcard c where c.packId = :packId")
    int deleteByPackId(@Param("packId") Long packId);
}
