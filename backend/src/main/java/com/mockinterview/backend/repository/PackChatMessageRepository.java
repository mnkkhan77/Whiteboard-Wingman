package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.PackChatMessage;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface PackChatMessageRepository extends JpaRepository<PackChatMessage, Long> {

    /** Newest first (id breaks ties between a question and its answer saved in the same instant);
     *  callers reverse for display. One query, no associations to fetch. */
    List<PackChatMessage> findByPackIdOrderByCreatedAtDescIdDesc(Long packId, Limit limit);

    /** Bulk delete — a derived deleteBy would load and delete every message one by one. */
    @Transactional
    @Modifying
    @Query("delete from PackChatMessage m where m.packId = :packId")
    int deleteByPackId(@Param("packId") Long packId);
}
