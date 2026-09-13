package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.Evaluation;
import com.mockinterview.backend.entity.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    @org.springframework.data.jpa.repository.Query(
        "select e from Evaluation e where e.answer.question.session = :session order by e.answer.question.sequenceNumber asc"
    )
    List<Evaluation> findBySessionOrderByQuestionSequence(InterviewSession session);
}
