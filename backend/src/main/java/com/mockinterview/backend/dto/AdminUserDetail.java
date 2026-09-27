package com.mockinterview.backend.dto;

import com.mockinterview.backend.entity.Role;
import com.mockinterview.backend.entity.Tier;

import java.time.LocalDateTime;
import java.util.List;

/** GET /admin/users/{id} — one user's profile plus full session history (PLAN.md §5). */
public record AdminUserDetail(
        Long id,
        String email,
        String displayName,
        Role role,
        Tier tier,
        LocalDateTime createdAt,
        LocalDateTime lastActiveAt,
        List<AdminSessionSummary> sessions
) {
}
