package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.Evaluation;
import com.mockinterview.backend.entity.InterviewSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvaluationRepository extends JpaRepository<Evaluation, Long> {

    /** Fetch-joins answer and question: every caller (report building, adaptive difficulty) reads
     *  e.getAnswer().getQuestion(), which would otherwise lazy-load 2 extra rows per evaluation. */
    @org.springframework.data.jpa.repository.Query(
        "select e from Evaluation e join fetch e.answer a join fetch a.question q"
            + " where q.session = :session order by q.sequenceNumber asc"
    )
    List<Evaluation> findBySessionOrderByQuestionSequence(InterviewSession session);

    /** Every evaluation with its answer and question in one query, for the admin score-by-difficulty
     *  stat — findAll() would lazy-load both per evaluation (2 extra selects per row). */
    @org.springframework.data.jpa.repository.Query(
        "select e from Evaluation e join fetch e.answer a join fetch a.question"
    )
    List<Evaluation> findAllWithQuestion();
}
