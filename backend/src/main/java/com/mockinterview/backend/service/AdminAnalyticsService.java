package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.AdminSessionSummary;
import com.mockinterview.backend.dto.AdminStats;
import com.mockinterview.backend.dto.AdminUserDetail;
import com.mockinterview.backend.dto.AdminUserSummary;
import com.mockinterview.backend.dto.WeeklySignupCount;
import com.mockinterview.backend.entity.Difficulty;
import com.mockinterview.backend.entity.Evaluation;
import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.Report;
import com.mockinterview.backend.entity.Topic;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.EvaluationRepository;
import com.mockinterview.backend.repository.InterviewSessionRepository;
import com.mockinterview.backend.repository.ReportRepository;
import com.mockinterview.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Aggregate queries backing the admin dashboard (PLAN.md §5, Phase 5). Deliberately loads the
 * (small, portfolio-scale) rows it needs into memory and aggregates with streams rather than
 * hand-writing a group-by JPQL query per metric — a pragmatic trade-off at this scale. No query
 * here is per-row of a page/table: listUsers batches its two lookups across the whole page, not
 * once per user, and getStats fetch-joins each evaluation's question instead of lazy-loading it.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAnalyticsService {

    private final UserRepository userRepository;
    private final InterviewSessionRepository sessionRepository;
    private final ReportRepository reportRepository;
    private final EvaluationRepository evaluationRepository;

    public Page<AdminUserSummary> listUsers(Pageable pageable) {
        Page<User> users = userRepository.findAll(pageable);
        List<User> usersOnPage = users.getContent();
        if (usersOnPage.isEmpty()) {
            return users.map(u -> null);
        }

        List<InterviewSession> sessions = sessionRepository.findByUserIn(usersOnPage);
        Map<Long, List<InterviewSession>> sessionsByUserId = sessions.stream()
                .collect(Collectors.groupingBy(s -> s.getUser().getId()));

        Map<Long, Double> averageScoreByUserId = reportRepository.findBySessionIn(sessions).stream()
                .collect(Collectors.groupingBy(
                        r -> r.getSession().getUser().getId(),
                        Collectors.averagingInt(Report::getOverallScore)));

        return users.map(u -> {
            List<InterviewSession> userSessions = sessionsByUserId.getOrDefault(u.getId(), List.of());
            Topic mostPracticed = mostFrequentTopic(userSessions);
            return new AdminUserSummary(
                    u.getId(), u.getEmail(), u.getDisplayName(), u.getRole(),
                    u.getCreatedAt(), u.getLastActiveAt(),
                    userSessions.size(), averageScoreByUserId.get(u.getId()), mostPracticed);
        });
    }

    public AdminUserDetail getUserDetail(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        List<InterviewSession> sessions = sessionRepository.findByUserOrderByCreatedAtDesc(user);
        Map<Long, Report> reportBySessionId = reportRepository.findBySessionIn(sessions).stream()
                .collect(Collectors.toMap(r -> r.getSession().getId(), r -> r));

        List<AdminSessionSummary> sessionSummaries = sessions.stream()
                .map(s -> new AdminSessionSummary(
                        s.getId(), s.getTopic(), s.getStatus(),
                        s.getStartingDifficulty(), s.getCurrentDifficulty(),
                        s.getQuestionsAsked(), s.getTargetQuestionCount(),
                        s.getCreatedAt(), s.getCompletedAt(),
                        reportBySessionId.containsKey(s.getId()) ? reportBySessionId.get(s.getId()).getOverallScore() : null))
                .toList();

        return new AdminUserDetail(
                user.getId(), user.getEmail(), user.getDisplayName(), user.getRole(),
                user.getCreatedAt(), user.getLastActiveAt(), sessionSummaries);
    }

    public AdminStats getStats() {
        List<User> allUsers = userRepository.findAll();
        List<InterviewSession> allSessions = sessionRepository.findAll();
        List<Report> allReports = reportRepository.findAll();
        List<Evaluation> allEvaluations = evaluationRepository.findAllWithQuestion();

        Map<Topic, Long> sessionsByTopic = allSessions.stream()
                .collect(Collectors.groupingBy(InterviewSession::getTopic, Collectors.counting()));

        Map<Topic, Double> averageScoreByTopic = allReports.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getSession().getTopic(),
                        Collectors.averagingInt(Report::getOverallScore)));

        Map<Difficulty, Double> averageScoreByDifficulty = allEvaluations.stream()
                .collect(Collectors.groupingBy(
                        e -> e.getAnswer().getQuestion().getDifficulty(),
                        Collectors.averagingInt(Evaluation::getScore)));

        List<WeeklySignupCount> signupsOverTime = allUsers.stream()
                .collect(Collectors.groupingBy(
                        u -> u.getCreatedAt().toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                        Collectors.counting()))
                .entrySet().stream()
                .map(e -> new WeeklySignupCount(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(WeeklySignupCount::weekStart))
                .toList();

        return new AdminStats(allUsers.size(), allSessions.size(), sessionsByTopic, averageScoreByTopic,
                averageScoreByDifficulty, signupsOverTime);
    }

    private Topic mostFrequentTopic(List<InterviewSession> sessions) {
        return sessions.stream()
                .collect(Collectors.groupingBy(InterviewSession::getTopic, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
    }
}
