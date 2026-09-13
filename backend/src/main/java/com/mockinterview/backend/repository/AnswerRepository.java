package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.Answer;
import com.mockinterview.backend.entity.Question;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnswerRepository extends JpaRepository<Answer, Long> {
    boolean existsByQuestion(Question question);
}
