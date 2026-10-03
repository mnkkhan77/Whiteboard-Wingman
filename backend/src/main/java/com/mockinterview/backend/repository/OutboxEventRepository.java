package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.OutboxEvent;
import com.mockinterview.backend.entity.OutboxStatus;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** Oldest pending rows first, capped — a single backend instance, so no cross-instance claim
     *  locking is needed (same assumption as the rest of the pipeline, e.g. PackQuizService). */
    List<OutboxEvent> findByStatusOrderByIdAsc(OutboxStatus status, Limit limit);
}
