package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {
    Optional<Report> findBySession(InterviewSession session);

    List<Report> findBySessionIn(List<InterviewSession> sessions);
}
