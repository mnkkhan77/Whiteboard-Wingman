package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.PersonalProgressResponse;
import com.mockinterview.backend.dto.ScorePoint;
import com.mockinterview.backend.entity.InterviewSession;
import com.mockinterview.backend.entity.Report;
import com.mockinterview.backend.entity.SessionStatus;
import com.mockinterview.backend.entity.Topic;
import com.mockinterview.backend.entity.User;
import com.mockinterview.backend.repository.InterviewSessionRepository;
import com.mockinterview.backend.repository.ReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Backs the personal progress dashboard (score trend + weakest topics for one user) — the same
 * "average score grouped by topic" aggregation as AdminAnalyticsService.getStats(), just scoped to
 * a single user's own sessions instead of the whole platform.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PersonalStatsService {

    private final InterviewSessionRepository sessionRepository;
    private final ReportRepository reportRepository;

    public PersonalProgressResponse getProgress(User user) {
        List<InterviewSession> sessions = sessionRepository.findByUserOrderByCreatedAtDesc(user);
        List<Report> reports = reportRepository.findBySessionIn(sessions);

        List<ScorePoint> scoreTrend = reports.stream()
                .sorted(Comparator.comparing(PersonalStatsService::effectiveCompletedAt))
                .map(r -> new ScorePoint(
                        r.getSession().getId(),
                        r.getSession().getTopic(),
                        effectiveCompletedAt(r),
                        r.getOverallScore()))
                .toList();

        Map<Topic, Double> averageScoreByTopic = reports.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getSession().getTopic(),
                        Collectors.averagingInt(Report::getOverallScore)));

        int completedSessions = (int) sessions.stream()
                .filter(s -> s.getStatus() == SessionStatus.COMPLETED)
                .count();

        Double overallAverageScore = reports.isEmpty() ? null
                : reports.stream().mapToInt(Report::getOverallScore).average().orElse(0);

        return new PersonalProgressResponse(
                scoreTrend, averageScoreByTopic, sessions.size(), completedSessions, overallAverageScore);
    }

    /** completedAt should always be set for a session with a report, but fall back to createdAt defensively. */
    private static LocalDateTime effectiveCompletedAt(Report report) {
        InterviewSession session = report.getSession();
        return session.getCompletedAt() != null ? session.getCompletedAt() : session.getCreatedAt();
    }
}
