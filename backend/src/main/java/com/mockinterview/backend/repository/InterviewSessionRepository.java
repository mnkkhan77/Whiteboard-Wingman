package com.mockinterview.backend.repository;

import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InterviewSessionRepository extends JpaRepository<InterviewSession, Long> {
    List<InterviewSession> findByUserOrderByCreatedAtDesc(User user);

    List<InterviewSession> findByUserIn(List<User> users);
}
