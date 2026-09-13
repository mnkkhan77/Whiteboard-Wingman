package com.mockinterview.backend.service;

import com.mockinterview.backend.dto.AdminStats;
import com.mockinterview.backend.dto.AdminUserDetail;
import com.mockinterview.backend.dto.AdminUserSummary;
import com.mockinterview.backend.entity.*;
import com.mockinterview.backend.repository.EvaluationRepository;
import com.mockinterview.backend.repository.InterviewSessionRepository;
import com.mockinterview.backend.repository.ReportRepository;
import com.mockinterview.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAnalyticsServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private InterviewSessionRepository sessionRepository;
    @Mock private ReportRepository reportRepository;
    @Mock private EvaluationRepository evaluationRepository;

    @InjectMocks
    private AdminAnalyticsService service;

    private User user(long id, String email) {
        User u = new User();
        u.setId(id);
        u.setEmail(email);
        u.setDisplayName("Name " + id);
        u.setRole(Role.USER);
        u.setCreatedAt(LocalDateTime.of(2026, 1, 5, 10, 0)); // a Monday
        return u;
    }

    private InterviewSession session(long id, User owner, Topic topic) {
        InterviewSession s = new InterviewSession();
        s.setId(id);
        s.setUser(owner);
        s.setTopic(topic);
        s.setStartingDifficulty(Difficulty.EASY);
        s.setCurrentDifficulty(Difficulty.EASY);
        s.setStatus(SessionStatus.COMPLETED);
        s.setTargetQuestionCount(1);
        s.setQuestionsAsked(1);
        return s;
    }

    private Report report(InterviewSession session, int score) {
        Report r = new Report();
        r.setSession(session);
        r.setOverallScore(score);
        return r;
    }

    @Test
    void listUsersReportsSessionCountAverageScoreAndMostPracticedTopicPerUser() {
        User alice = user(1L, "alice@example.com");
        InterviewSession dsa1 = session(1L, alice, Topic.DSA);
        InterviewSession dsa2 = session(2L, alice, Topic.DSA);
        InterviewSession spring1 = session(3L, alice, Topic.SPRING);

        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(alice), pageable, 1));
        when(sessionRepository.findByUserIn(List.of(alice))).thenReturn(List.of(dsa1, dsa2, spring1));
        when(reportRepository.findBySessionIn(List.of(dsa1, dsa2, spring1)))
                .thenReturn(List.of(report(dsa1, 80), report(dsa2, 90)));

        List<AdminUserSummary> result = service.listUsers(pageable).getContent();

        assertThat(result).hasSize(1);
        AdminUserSummary summary = result.get(0);
        assertThat(summary.sessionCount()).isEqualTo(3);
        assertThat(summary.averageScore()).isEqualTo(85.0); // (80+90)/2, spring1 has no report yet
        assertThat(summary.mostPracticedTopic()).isEqualTo(Topic.DSA); // 2 DSA sessions vs 1 SPRING
    }

    @Test
    void listUsersLeavesAverageScoreNullForAUserWithNoReportsYet() {
        User bob = user(2L, "bob@example.com");
        InterviewSession session = session(4L, bob, Topic.SPRING);

        Pageable pageable = PageRequest.of(0, 20);
        when(userRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(bob), pageable, 1));
        when(sessionRepository.findByUserIn(List.of(bob))).thenReturn(List.of(session));
        when(reportRepository.findBySessionIn(List.of(session))).thenReturn(List.of());

        AdminUserSummary summary = service.listUsers(pageable).getContent().get(0);

        assertThat(summary.averageScore()).isNull();
    }

    @Test
    void getUserDetailMarksSessionsWithNoReportYetAsNullScoreRatherThanZero() {
        User alice = user(1L, "alice@example.com");
        InterviewSession completedWithReport = session(1L, alice, Topic.DSA);
        InterviewSession inProgressNoReport = session(2L, alice, Topic.SPRING);
        inProgressNoReport.setStatus(SessionStatus.IN_PROGRESS);

        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(alice));
        when(sessionRepository.findByUserOrderByCreatedAtDesc(alice))
                .thenReturn(List.of(completedWithReport, inProgressNoReport));
        when(reportRepository.findBySessionIn(List.of(completedWithReport, inProgressNoReport)))
                .thenReturn(List.of(report(completedWithReport, 77)));

        AdminUserDetail detail = service.getUserDetail(1L);

        assertThat(detail.sessions()).hasSize(2);
        assertThat(detail.sessions().get(0).overallScore()).isEqualTo(77);
        assertThat(detail.sessions().get(1).overallScore()).isNull();
    }

    @Test
    void getStatsAggregatesSessionsByTopicScoresByDifficultyAndWeeklySignups() {
        User alice = user(1L, "alice@example.com");
        User bob = user(2L, "bob@example.com");
        bob.setCreatedAt(LocalDateTime.of(2026, 1, 6, 9, 0)); // same week (Tuesday) as alice's Monday

        InterviewSession dsaSession = session(1L, alice, Topic.DSA);
        InterviewSession springSession = session(2L, bob, Topic.SPRING);

        when(userRepository.count()).thenReturn(2L);
        when(userRepository.findAll()).thenReturn(List.of(alice, bob));
        when(sessionRepository.findAll()).thenReturn(List.of(dsaSession, springSession));
        when(reportRepository.findAll()).thenReturn(List.of(report(dsaSession, 60), report(springSession, 100)));

        Question easyQuestion = new Question();
        easyQuestion.setDifficulty(Difficulty.EASY);
        Answer easyAnswer = new Answer();
        easyAnswer.setQuestion(easyQuestion);
        Evaluation easyEval = new Evaluation();
        easyEval.setAnswer(easyAnswer);
        easyEval.setScore(70);

        Question hardQuestion = new Question();
        hardQuestion.setDifficulty(Difficulty.HARD);
        Answer hardAnswer = new Answer();
        hardAnswer.setQuestion(hardQuestion);
        Evaluation hardEval = new Evaluation();
        hardEval.setAnswer(hardAnswer);
        hardEval.setScore(50);

        when(evaluationRepository.findAll()).thenReturn(List.of(easyEval, hardEval));

        AdminStats stats = service.getStats();

        assertThat(stats.totalUsers()).isEqualTo(2);
        assertThat(stats.totalSessions()).isEqualTo(2);
        assertThat(stats.sessionsByTopic()).containsEntry(Topic.DSA, 1L).containsEntry(Topic.SPRING, 1L);
        assertThat(stats.averageScoreByTopic()).containsEntry(Topic.DSA, 60.0).containsEntry(Topic.SPRING, 100.0);
        assertThat(stats.averageScoreByDifficulty()).containsEntry(Difficulty.EASY, 70.0).containsEntry(Difficulty.HARD, 50.0);
        assertThat(stats.signupsOverTime()).hasSize(1); // both signups fall in the same Mon-Sun week
        assertThat(stats.signupsOverTime().get(0).count()).isEqualTo(2);
    }
}
