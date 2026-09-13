package com.mockinterview.backend.controller;

import com.mockinterview.backend.dto.AdminStats;
import com.mockinterview.backend.dto.AdminUserDetail;
import com.mockinterview.backend.dto.AdminUserSummary;
import com.mockinterview.backend.dto.IngestionSummary;
import com.mockinterview.backend.service.AdminAnalyticsService;
import com.mockinterview.backend.service.ContentIngestionService;
import com.mockinterview.backend.service.StaticQuestionBankService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Gated by SecurityConfig's ".requestMatchers("/api/admin/**").hasRole("ADMIN")" (PLAN.md §5).
 * /ingest is a dev/ops action (re-run when handbook content changes); /users, /users/{id} and
 * /stats (Phase 5) back the read-only AdminDashboardPage — see AdminAnalyticsService for the
 * aggregate queries (PLAN.md §12).
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final ContentIngestionService contentIngestionService;
    private final AdminAnalyticsService adminAnalyticsService;
    private final StaticQuestionBankService staticQuestionBankService;

    @PostMapping("/ingest")
    public IngestionSummary ingest() {
        IngestionSummary summary = contentIngestionService.ingestAll();
        staticQuestionBankService.refreshRemoteBanks();
        return summary;
    }

    @GetMapping("/users")
    public Page<AdminUserSummary> listUsers(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return adminAnalyticsService.listUsers(pageable);
    }

    @GetMapping("/users/{id}")
    public AdminUserDetail getUserDetail(@PathVariable Long id) {
        return adminAnalyticsService.getUserDetail(id);
    }

    @GetMapping("/stats")
    public AdminStats getStats() {
        return adminAnalyticsService.getStats();
    }
}
