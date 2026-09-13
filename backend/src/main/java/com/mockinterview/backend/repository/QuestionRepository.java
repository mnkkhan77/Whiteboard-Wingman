package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface QuestionRepository extends JpaRepository<Question, Long> {
    List<Question> findBySessionOrderBySequenceNumberAsc(InterviewSession session);
    Optional<Question> findTopBySessionOrderBySequenceNumberDesc(InterviewSession session);
}
